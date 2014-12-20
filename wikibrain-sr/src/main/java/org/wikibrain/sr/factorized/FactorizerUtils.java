package org.wikibrain.sr.factorized;

import gnu.trove.map.TIntFloatMap;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.io.FileUtils;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.matrix.*;
import org.wikibrain.utils.WbMathUtils;
import org.wikibrain.utils.WpCollectionUtils;
import org.wikibrain.utils.WpIOUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Shilad Sen
 */
public class FactorizerUtils {
    /**
     * Writes a matrix to a textual format contain three files:
     *
     *
     * @param dir
     */
    public void writeTextFormat(Matrix<MatrixRow> matrix, File dir, LocalPageDao pageDao, Language language) throws IOException, DaoException {
        dir.mkdirs();

        // Write row ids
        BufferedWriter w = WpIOUtils.openWriter(FileUtils.getFile(dir, "rows.txt"));
        int i = 1;
        for (int rowId : matrix.getRowIds()) {
            LocalPage p = pageDao.getById(language, rowId);
            String title = (p == null) ? "unknown" : p.getTitle().getCanonicalTitle();
            w.write((i++) + "\t" + rowId + "\t" + title + "\n");
        }
        w.close();

        // Write matrix
        TIntIntMap colIdMap = new TIntIntHashMap();
        w = WpIOUtils.openWriter(FileUtils.getFile(dir, "matrix.txt"));
        for (MatrixRow row : matrix) {
            for (int j = 0; j < row.getNumCols(); j++) {
                if (j != 0) { w.write(' '); }
                int colId = row.getColIndex(j);
                int denseColId;
                if (colIdMap.containsKey(colId)) {
                    denseColId = colIdMap.get(colId);
                } else {
                    denseColId = colIdMap.size() + 1;
                    colIdMap.put(colId, denseColId);
                }
                w.write(denseColId + ":" + row.getColValue(j));
            }
            w.write('\n');
        }
        w.close();

        // Write col ids
        w = WpIOUtils.openWriter(FileUtils.getFile(dir, "cols.txt"));
        int colIds[] = WpCollectionUtils.sortMapKeys(colIdMap);
        for (i = 0; i < colIds.length; i++) {
            int colId = colIds[i];
            int denseId = colIdMap.get(colId);
            if (denseId != (i+1)) throw new IllegalStateException();
            LocalPage p = pageDao.getById(language, colId);
            String title = (p == null) ? "unknown" : p.getTitle().getCanonicalTitle();
            w.write((i++) + "\t" + colId + "\t" + title + "\n");
        }
        w.close();
    }

    public static InMemorySparseMatrix makeSymmetric(Matrix<? extends MatrixRow> m) throws IOException {
        double meanMin = 0;
        Map<Integer, TIntFloatMap> rows = new HashMap<Integer, TIntFloatMap>();
        for (MatrixRow row : m) {
            if (row.getNumCols() == 0) continue;
            double min = Double.MAX_VALUE;
            for (int i = 0; i < row.getNumCols(); i++) {
                min = Math.min(row.getColValue(i), min);
            }
            meanMin += min;
            rows.put(row.getRowIndex(), row.asTroveMap());
        }
        meanMin /= m.getNumRows();
        System.err.println("meanMin is " + meanMin + " using empty value " + meanMin * 0.5);
        meanMin *= 0.7;
        for (int rowId : rows.keySet()) {
            for (int colId : rows.get(rowId).keys()) {
                if (rowId < colId) continue;
                float val = rows.get(rowId).get(colId);
                if (rows.containsKey(colId) && rows.get(colId).containsKey(rowId)) {
                    val = 0.5f * val + 0.5f * rows.get(colId).get(rowId);
                    rows.get(rowId).put(colId, val);
                    rows.get(colId).put(rowId, val);
                } else {
                    val = (float) (0.5f * val + 0.5f * meanMin);
                    rows.get(rowId).put(colId, val);
                    rows.get(colId).put(rowId, val);
                }
            }
        }
        int rowIds[] = m.getRowIds();
        int colIds[][] = new int[rowIds.length][];
        double vals[][] = new double[rowIds.length][];
        for (int i = 0; i < rowIds.length; i++) {
            MatrixRow row = m.getRow(rowIds[i]);
            colIds[i] = new int[row.getNumCols()];
            vals[i] = new double[row.getNumCols()];
            for (int j = 0; j < row.getNumCols(); j++) {
                colIds[i][j] = row.getColIndex(j);
                vals[i][j] = rows.get(rowIds[i]).get(colIds[i][j]);
            }
        }
        return new InMemorySparseMatrix(rowIds, colIds, vals);
    }
}
