package io;

import com.google.api.client.http.InputStreamContent;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.StorageObject;
import helper.FSUtil;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.jblas.DoubleMatrix;
import org.jblas.Solve;
import org.jblas.ranges.IndicesRange;
import org.jblas.ranges.IntervalRange;
import scala.Tuple2;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;


public class AdsInputReader extends FSInputReader
{
    private static final int ADS_FEATURE_SIZE = 1558;
    private static final int DOROTHEA_FEATURE_SIZE = 100000;
    private Broadcast bcFeatures;
    private Broadcast<double[]> bcInstances;
    private JavaRDD<XYMatrix> xyMatrix;
    long startTime = System.currentTimeMillis();

    /**
     * Initiate input file name to Internet Advertisements dataset
     * (https://archive.ics.uci.edu/ml/datasets/Internet+Advertisements)
     * from UCI Machine Learning Repository.
     */
    public AdsInputReader(String filename, int numOfExecutors)
    {
        super(filename, numOfExecutors);
    }

    /**
     * Run feature selection.
     */
    public void process(int loopNumber, String outputName, String datasetName, String bucketName)
    {
        /* define number of original features in the dataset */
        int numberOfFeatures;

        if(datasetName.contains("dorothea")) {
            numberOfFeatures = DOROTHEA_FEATURE_SIZE;
        } else {
            numberOfFeatures = ADS_FEATURE_SIZE;
        }

        bcFeatures = getSparkContext().broadcast(numberOfFeatures);

        /* read data from input file */
        JavaRDD<List<String[]>> rawData = getRawData().mapPartitions(iterator -> {
            List<String[]> list = new ArrayList<>();

            while(iterator.hasNext()){
                list.add(iterator.next().split(" "));
            }

            return Collections.singleton(list);
        });

        countClasses(rawData);
        DoubleMatrix score = computeFeatureScores(rawData, bcFeatures, bcInstances);
        Set<Integer> selectedFeatures = getBestFeatures(score, loopNumber);
        DoubleMatrix subMatrix = getSubMatrix(selectedFeatures);

        System.out.println("Selected indexes: " + selectedFeatures);

        /* write output to file and statistics */
        try {
            write(subMatrix, bcInstances, outputName);
            uploadFile("output/" + outputName, "text/plain", new File(outputName), bucketName);
        } catch(Exception e) {
            e.printStackTrace();
        }

        printStats(bcInstances, subMatrix.columns, subMatrix.rows);

    }

    /**
     * Copyright (c) 2014 Google Inc.
     *
     * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
     * in compliance with the License. You may obtain a copy of the License at
     *
     * http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software distributed under the License
     * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
     * or implied. See the License for the specific language governing permissions and limitations under
     * the License.
     *
     * Uploads data to an object in a bucket.
     * Modified from: https://github.com/GoogleCloudPlatform/java-docs-samples/blob/master/storage/json-api/src/main/java/StorageSample.java
     *
     */
    public static void uploadFile(
            String name, String contentType, File file, String bucketName)
            throws IOException, GeneralSecurityException
    {
        InputStreamContent contentStream = new InputStreamContent(contentType, new FileInputStream(file));
        // Setting the length improves upload performance
        contentStream.setLength(file.length());
        StorageObject objectMetadata = new StorageObject()
                // Set the destination object name
                .setName(name)
                // Set the access control list to publicly read-only
                .setAcl(Arrays.asList(
                        new ObjectAccessControl().setEntity("allUsers").setRole("READER")));

        // Do the insert
        Storage client = StorageFactory.getService();
        Storage.Objects.Insert insertRequest = client.objects().insert(
                bucketName, objectMetadata, contentStream);

        insertRequest.execute();
    }

    /**
     * Count number of instances in each class and compute the values for response matrix.
     * @param logData input data
     */
    private void countClasses(JavaRDD<List<String[]>> logData)
    {
        /* map values in class column into pair of <class, 1> */
        JavaPairRDD<String, Integer> pairs = logData.mapPartitionsToPair(iterator -> {
            List<Tuple2<String, Integer>> list = new ArrayList<>();
            Map<String, Integer> map = new HashMap<>();

            while(iterator.hasNext()){
                List<String[]> temp = iterator.next();
                for(String[] str : temp) {
                    String label = str[0];

                    int size = 0;
                    if (map.containsKey(label)) {
                        size = map.get(label) + 1;
                    }

                    map.put(label, size);
                }

                for(Map.Entry<String, Integer> entry : map.entrySet()){
                    list.add(new Tuple2<>(entry.getKey(), entry.getValue()));
                }
            }

            return list;
        });

        // count number of items/points per class
        JavaPairRDD<String, Integer> counts = pairs.reduceByKey((a, b) -> a + b);
        List<Tuple2<String, Integer>> list = counts.collect();

        double[] instances = new double[7];

        // specific for ad/nonad classes
        if(list.get(0)._1().equals("1")){
            instances[1] = list.get(0)._2(); // number of positive data points
            instances[2] = list.get(1)._2(); // number of negative data points
        } else{
            instances[1] = list.get(1)._2(); // number of positive data points
            instances[2] = list.get(0)._2(); // number of negative data points
        }

        // as formula (4) in the paper.
        instances[0] = instances[1] + instances[2]; // number of instances = positive + negative

        // yPos[0] = instances[3], yPos[1] = instances[4]
        instances[4] = - Math.sqrt(instances[1]) / instances[0];
        instances[3] = 1.0 / Math.sqrt(instances[1]) + instances[4];

        // yNeg[0] = instances[5], yNeg[1] = instances[6]
        instances[6] = - Math.sqrt(instances[2]) / instances[0];
        instances[5] = 1.0 / Math.sqrt(instances[2]) + instances[6];

        bcInstances = getSparkContext().broadcast(instances);
    }


    /**
     * Compute feature scores E and v based on algorithm step 1-3
     * X (features matrix) consists of features.
     * Y (response matrix) consists of response calculated from formula (4) in the paper using class labels
     * @param logData input data
     * @return s scores for each features in a feature matrix
     */
    private DoubleMatrix computeFeatureScores(JavaRDD<List<String[]>> logData, Broadcast bcFeatures, Broadcast<double[]> bcInstances)
    {
        double[] instances = bcInstances.getValue();
        Double[] bcYPos = new Double[]{instances[3], instances[4]};
        Double[] bcYNeg = new Double[]{instances[5], instances[6]};

        // map values into pairs of X (features matrix) and Y (response matrix)
        xyMatrix = logData.mapPartitions(iterator -> {
            ArrayList<Double[]> featureMatrix = new ArrayList<>();
            ArrayList<Double[]> responseMatrix = new ArrayList<>();

            while(iterator.hasNext()) {
                List<String[]> list = iterator.next();
                for(String[] splittedLine : list) {
                    featureMatrix.add(getFeatures(splittedLine, bcFeatures));
                    if (splittedLine[0].equals("1")) {
                        responseMatrix.add(bcYPos);
                    } else {
                        responseMatrix.add(bcYNeg);
                    }
                }
            }

            DoubleMatrix x = new DoubleMatrix(FSUtil.convertToDoubleArray(featureMatrix));
            DoubleMatrix y = new DoubleMatrix(FSUtil.convertToDoubleArray(responseMatrix));

            return Collections.singleton(new XYMatrix(x, y));
        }).cache();

        JavaRDD<FeatureScore> fScoreMatrix = xyMatrix.map(matrix -> {
            DoubleMatrix x = matrix.getX();
            DoubleMatrix y = matrix.getY();

            DoubleMatrix ones = DoubleMatrix.ones(x.getRows());

            return new FeatureScore(y.transpose().mmul(x), ones.transpose().mmul(x.mul(x)));
        });

        FeatureScore totalScore = fScoreMatrix.reduce((a, b) -> a.add(b));

        DoubleMatrix e = totalScore.getEMatrix();
        DoubleMatrix v = totalScore.getVMatrix();
        DoubleMatrix s = DoubleMatrix.ones(e.getRows()).transpose().mmul(e.mul(e));

        // Element-wise division on matrix = div ("divi" will replace the original matrix)
        s = s.div(v);

        return s;
    }

    /**
     * Select best features based on precomputed scores.
     * @param score precomputed scores
     * @return index of selected features
     */
    private Set<Integer> getBestFeatures(DoubleMatrix score, int loopNumber)
    {
        Set<Integer> set = new HashSet<>();
        int maxIndex = score.argmax(), k = loopNumber, l = 1;
        set.add(maxIndex);

        DoubleMatrix cAcc = null;

        while(l < k)
        {
            Broadcast broadcastIdx = getSparkContext().broadcast(maxIndex);

            // step 8
            JavaRDD<DoubleMatrix> ci = xyMatrix.map(matrix -> {
                DoubleMatrix x = matrix.getX();
                DoubleMatrix f = x.getColumn((int)broadcastIdx.value());
                DoubleMatrix c = x.transpose().mmul(f);
                return c;
            });

            // step 9
            if(cAcc == null) {
                cAcc = ci.reduce((a, b) -> a.add(b));
            } else{
                cAcc = DoubleMatrix.concatHorizontally(cAcc, ci.reduce((a, b) -> a.add(b)));
            }

            int selectedIndexes[] = new int[l];
            int unSelectedIndexes[] = new int[cAcc.rows - l];

            int i = 0, j = 0;

            for(Integer idx : set){
                selectedIndexes[i++] = idx;
            }

            System.out.println(set.size() + " " + cAcc.rows + " " + l + " " + unSelectedIndexes.length);

            for(i = 0; i < cAcc.rows; i++){
                if(!set.contains(i)) {
                    unSelectedIndexes[j++] = i;
                }
            }

            DoubleMatrix s = getNextScore(selectedIndexes, unSelectedIndexes, xyMatrix);
            maxIndex = s.argmax();

            if(set.contains(maxIndex)){
                set.add(getIndexOfMaxValue(set, s));
            } else {
                set.add(maxIndex);
            }

            l++;

            if(l % 50 == 0){
                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                System.out.println("Time to proceed : " + l + " data = " +  totalTime/1000 + " s " + totalTime%1000 + " ms");
            }

            if(set.size() != l){// to stop when the rest of the values are the same
                break;
            }
        }

        System.out.println("Total selected indexes: " + set.size() + " / " + k);

        return set;
    }

    private int getIndexOfMaxValue(Set<Integer> set, DoubleMatrix s){
        int idx = 0;
        double value = 0;

        for(int i = 0; i < s.columns; i++){
            if(!set.contains(i) && s.get(0, i) >= value){
                value = s.get(0, i);
                idx = i;
            }
        }

        return idx;
    }

    /**
     * Iteratively update feature score based on selected and unselected features.
     * @param selectedIndexes index of selected features
     * @param unselectedIndexes index of unselected features
     * @param logData input file
     * @return matrix of feature scores
     */
    private DoubleMatrix getNextScore(int selectedIndexes[], int unselectedIndexes[], JavaRDD<XYMatrix> logData)
    {
        Broadcast broadcastSelectedIndexes = getSparkContext().broadcast(selectedIndexes);
        Broadcast broadcastUnselectedIndexes = getSparkContext().broadcast(unselectedIndexes);

        // step 10
        JavaRDD<FeatureMatrices> temp = logData.map(matrix -> {
            DoubleMatrix x = matrix.getX();
            DoubleMatrix y = matrix.getY();

            DoubleMatrix x1 = x.get(new IntervalRange(0, x.getRows()), new IndicesRange((int[])broadcastSelectedIndexes.getValue()));
            DoubleMatrix x2 = x.get(new IntervalRange(0, x.getRows()), new IndicesRange((int[])broadcastUnselectedIndexes.getValue()));

            DoubleMatrix ones = DoubleMatrix.ones(x.getRows());

            DoubleMatrix matrixA = x1.transpose().mmul(x1);
            DoubleMatrix matrixCY1 = y.transpose().mmul(x1);
            DoubleMatrix matrixCY2 = y.transpose().mmul(x2);
            DoubleMatrix matrixC12 = x1.transpose().mmul(x2);
            DoubleMatrix matrixV2 = ones.transpose().mmul(x2.mul(x2));

            return new FeatureMatrices(matrixA, matrixCY1, matrixCY2, matrixC12, matrixV2);
        });

        // step 11
        FeatureMatrices featureMatrices = temp.reduce((a, b) -> a.add(b));
        DoubleMatrix matrixB = Solve.pinv(featureMatrices.getMatrixA()).mmul(featureMatrices.getMatrixC12());
        DoubleMatrix matrixH = featureMatrices.getMatrixCY1().mmul(matrixB);
        DoubleMatrix matrixG = featureMatrices.getMatrixCY2().sub(matrixH);

        DoubleMatrix g = DoubleMatrix.ones(matrixG.getRows()).transpose().mmul(matrixG.mul(matrixG));
        DoubleMatrix w = featureMatrices.getMatrixV2().sub(DoubleMatrix.ones(
                featureMatrices.getMatrixC12().getRows()).transpose().mmul(
                featureMatrices.getMatrixC12().mul(matrixB)));

        DoubleMatrix s = g.div(w);

        return s;
    }

    /**
     * Get all features per data point
     * @param cells cells per row in input file
     * @return features in a double array
     */
    private static Double[] getFeatures(String cells[], Broadcast bcNumOfFeatures)
    {

        Double features[] = new Double[(Integer)bcNumOfFeatures.value()];
        int j = 1;

        for(int i = 1; i < cells.length; i++) {
            String temp[] = cells[i].split(":");
            int featureId = Integer.parseInt(temp[0]);

            while(j < featureId){
                features[j-1] = 0.0;
                j++;
            }

            features[j-1] = Double.parseDouble(temp[1]);
            j++;
        }

        while(j <= (Integer)bcNumOfFeatures.value()) {
            features[j-1] = 0.0;
            j++;
        }

        return features;
    }

    /**
     * Get the sub matrix of selected features
     * @param ids indexes of selected features in the original matrix
     * @return sub matrix with values of selected features
     */
    private DoubleMatrix getSubMatrix(Set<Integer> ids)
    {
        int temp[] = new int[ids.size()];
        int i = 0;

        for(Integer id : ids){
            temp[i++] = id;
        }

        Arrays.sort(temp);
        Broadcast broadcastSelectedIndexes = getSparkContext().broadcast(temp);

        JavaRDD<DoubleMatrix> subMatrix = xyMatrix.map(matrix -> {
            DoubleMatrix x = matrix.getX();
            DoubleMatrix x1 = x.get(new IntervalRange(0, x.getRows()), new IndicesRange((int[])broadcastSelectedIndexes.getValue()));

            return DoubleMatrix.concatHorizontally(x1, matrix.getY());
        });

        DoubleMatrix subMatrixCombined = subMatrix.reduce((a, b) -> DoubleMatrix.concatVertically(a, b));

        return subMatrixCombined;
    }

    /**
     * To write selected features to a file.
     * @param subMatrix values of matrix from selected features
     * @param outputName output file name
     * @throws Exception
     */
    private void write(DoubleMatrix subMatrix, Broadcast<double[]> bcInstances, String outputName) throws Exception
    {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outputName))));
        StringBuffer buffer = new StringBuffer();

        int column = subMatrix.columns - 2;
        double[] instances = bcInstances.getValue();

        for(int i = 0; i < subMatrix.rows; i++)
        {
            // if the value equals to yNeg[0]
            if(subMatrix.get(i, column) == instances[5]) {
                buffer.append("0 "); // negative
            } else {
                buffer.append("1 "); // positive
            }

            int k = 1;

            for(int j = 0; j < column; j++) {
                double value = subMatrix.get(i, j);
                if (value != 0) {
                    buffer.append(k);
                    buffer.append(":");
                    buffer.append(subMatrix.get(i, j));
                    buffer.append(" ");
                }
                k++;
            }

            buffer.deleteCharAt(buffer.length() - 1);
            buffer.append("\n");
        }

        writer.write(buffer.toString());
        writer.flush();
        writer.close();
    }

    /**
     * Print out data statistics like number of instances and class distribution.
     */
    private void printStats(Broadcast<double[]> bcInstances, int col, int row)
    {
        double[] instances = bcInstances.getValue();
        System.out.println("# instances: " + (int)instances[0] + " (pos: " + (int)instances[1] + ", neg: " + (int)instances[2] + ")");
        System.out.println("yPos: [" + instances[3] + "," + instances[4] + "]");
        System.out.println("yNeg: [" + instances[5] +"," + instances[6] + "]");
        System.out.println("result size: col:" + (col-2) +", row: " + row);
    }
}
