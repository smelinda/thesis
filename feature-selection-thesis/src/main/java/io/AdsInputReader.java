package io;

import helper.FSUtil;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.jblas.DoubleMatrix;
import org.jblas.Solve;
import org.jblas.ranges.IndicesRange;
import org.jblas.ranges.IntervalRange;
import scala.Tuple2;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.*;


public class AdsInputReader extends FSInputReader
{
    private static final int ADS_FEATURE_SIZE = 1558;
    private static final int DOROTHEA_FEATURE_SIZE = 100000;
    private static String datasetName;
    private int numberOfInstances = 0;
    private int pos = 0;
    private int neg = 0;
    private static Double yPos[] = new Double[2];
    private static Double yNeg[] = new Double[2];
    private JavaRDD<XYMatrix> xyMatrix;

    /**
     * Initiate input file name to Internet Advertisements dataset
     * (https://archive.ics.uci.edu/ml/datasets/Internet+Advertisements)
     * from UCI Machine Learning Repository.
     */
    public AdsInputReader(String filename, String datasetName)
    {
        super(filename);
        this.datasetName = datasetName;
    }

    /**
     * Run feature selection.
     */
    public void process(int loopNumber, String outputFileName)
    {
        JavaRDD<List<String[]>> rawData = getRawData().mapPartitions(iterator -> {
            List<String[]> list = new ArrayList<>();

            while(iterator.hasNext()){
                list.add(iterator.next().split(" "));
            }

            return Collections.singleton(list);
        });

        countClasses(rawData);
        DoubleMatrix score = computeFeatureScores(rawData);
        DoubleMatrix subMatrix = getSubMatrix(getBestFeatures(score, loopNumber));

        try {
            write(subMatrix, outputFileName);
        } catch(Exception e) {
            e.printStackTrace();
        }

        printStats(subMatrix.columns, subMatrix.rows);

    }

    /**
     * Count number of instances in each class and compute the values for response matrix.
     * @param logData input data
     */
    private void countClasses(JavaRDD<List<String[]>> logData)
    {
        // map values in class column into pair of <class, 1>
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

        // specific for ad/nonad classes
        if(list.get(0)._1().equals("1")){
            pos = list.get(0)._2();
            neg = list.get(1)._2();
        } else{
            pos = list.get(1)._2();
            neg = list.get(0)._2();
        }

        // as formula (4) in the paper.
        numberOfInstances = pos + neg;

        yPos[1] = - Math.sqrt(pos) / numberOfInstances;
        yPos[0] = 1.0 / Math.sqrt(pos) + yPos[1];

        yNeg[1] = - Math.sqrt(neg) / numberOfInstances;
        yNeg[0] = 1.0 / Math.sqrt(neg) + yNeg[1];
    }


    /**
     * Compute feature scores E and v based on algorithm step 1-3
     * X (features matrix) consists of features.
     * Y (response matrix) consists of response calculated from formula (4) in the paper using class labels
     * @param logData input data
     * @return s scores for each features in a feature matrix
     */
    private DoubleMatrix computeFeatureScores(JavaRDD<List<String[]>> logData)
    {
        // map values into pairs of X (features matrix) and Y (response matrix)
        xyMatrix = logData.mapPartitions(iterator -> {
            ArrayList<Double[]> featureMatrix = new ArrayList<>();
            ArrayList<Double[]> responseMatrix = new ArrayList<>();
            ArrayList<String> labelVector = new ArrayList<>();

            while(iterator.hasNext()) {
                List<String[]> list = iterator.next();
                for(String[] splittedLine : list) {
                    featureMatrix.add(getFeatures(splittedLine));

                    labelVector.add(splittedLine[0]);

                    if (splittedLine[0].equals("1")) {
                        responseMatrix.add(yPos);
                    } else {
                        responseMatrix.add(yNeg);
                    }
                }
            }

            DoubleMatrix x = new DoubleMatrix(FSUtil.convertToDoubleArray(featureMatrix));
            DoubleMatrix y = new DoubleMatrix(FSUtil.convertToDoubleArray(responseMatrix));


            return Collections.singleton(new XYMatrix(x, y, labelVector));
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

        System.out.println("#rows of s:" + s.rows);
        System.out.println("#columns of s:" + s.columns);

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

            for(i = 0; i < cAcc.rows; i++){
                if(!set.contains(i)) {
                    unSelectedIndexes[j++] = i;
                }
            }

            DoubleMatrix s = getNextScore(selectedIndexes, unSelectedIndexes, xyMatrix);
            maxIndex = s.argmax();
            set.add(maxIndex);
            l++;

        }

        return set;
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

        System.out.println("Selected: " + selectedIndexes.length + " Unselected: " + unselectedIndexes.length);

        // step 10
        JavaRDD<FeatureMatrices> temp = logData.map(matrix -> {
            DoubleMatrix x = matrix.getX();
            DoubleMatrix y = matrix.getY();

            DoubleMatrix x1 = x.get(new IntervalRange(0, x.getRows()), new IndicesRange((int[])broadcastSelectedIndexes.getValue()));
            DoubleMatrix x2 = x.get(new IntervalRange(0, x.getRows()), new IndicesRange((int[])broadcastUnselectedIndexes.getValue()));

//			System.out.println("Dimension x1: " + x1.getRows() + " " + x1.getColumns());
//			System.out.println("Dimension x2: " + x2.getRows() + " " + x2.getColumns());

            DoubleMatrix ones = DoubleMatrix.ones(x.getRows());

            DoubleMatrix matrixA = x1.transpose().mmul(x1);
            DoubleMatrix matrixCY1 = y.transpose().mmul(x1);
            DoubleMatrix matrixCY2 = y.transpose().mmul(x2);
            DoubleMatrix matrixC12 = x1.transpose().mmul(x2);
            DoubleMatrix matrixV2 = ones.transpose().mmul(x2.mul(x2));

//			System.out.println(matrixA.getRows() + " " + matrixA.getColumns());

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
    private static Double[] getFeatures(String cells[])
    {
        int numberOfFeature;
        if(datasetName.equals("ads"))
            numberOfFeature = ADS_FEATURE_SIZE;
        else
            numberOfFeature = DOROTHEA_FEATURE_SIZE;

        Double features[] = new Double[numberOfFeature];
        int j = 1;

        for(int i = 1; i < cells.length; i++) {
            String temp[] = cells[i].split(":");
            int featureId = Integer.parseInt(temp[0]);
            // To treat missing values, we convert them to zero.

            while(j < featureId){
                features[j-1] = 0.0;
                j++;
            }

            features[j-1] = Double.parseDouble(temp[1]);
            j++;
        }

        while(j <= numberOfFeature) {
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

        System.out.print("Selected indexes: ");
        for(Integer id : ids){
            temp[i++] = id;
            System.out.print(id + ",");
        }

        System.out.print("\n");
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
    private void write(DoubleMatrix subMatrix, String outputName) throws Exception
    {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outputName))));
        StringBuffer buffer = new StringBuffer();

        int column = subMatrix.columns - 2;

        for(int i = 0; i < subMatrix.rows; i++){
            if(subMatrix.get(i, column - 2) == yPos[0]) {
                buffer.append("1 "); // positive
            } else {
                buffer.append("0 "); // negative
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
    private void printStats(int col, int row)
    {
        System.out.println("# instances:" + numberOfInstances + "(pos:" + pos + ", neg:" + neg + ")");
        System.out.println("yPos: [" + yPos[0] + "," + yPos[1] + "]");
        System.out.println("yNeg: [" + yNeg[0] +"," + yNeg[1] + "]");
        System.out.println("result size: col:" + (col-2) +", row: " + row);
    }
}
