package org.mp.naumann.algorithms.implementations;

import org.apache.commons.io.FileUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mp.naumann.algorithms.implementations.helper.DatabaseHelper;
import org.mp.naumann.algorithms.result.SimpleObjectResultSet;
import org.mp.naumann.database.DataConnector;
import org.mp.naumann.database.jdbc.JdbcDataConnector;

import java.io.File;
import java.io.IOException;

import static junit.framework.TestCase.assertEquals;

public class MedianInitialAlgorithmTest {

    private static DataConnector dataConnector;
    private static MedianInitialAlgorithm algorithm;

    protected static final String tableName = "median";
    protected static final String columnName = "age";

    @BeforeClass
    public static void setUp() throws IOException {
        DatabaseHelper.prepareDataset();
        dataConnector = DatabaseHelper.getDataConnector();
        algorithm = new MedianInitialAlgorithm(dataConnector, tableName, columnName);
    }

    @Test
    public void testExecute(){
        // As CSV files are loaded as String tables, this method
        // sorts the numbers alphabetically - not numberwise.
        SimpleObjectResultSet result = (SimpleObjectResultSet)algorithm.execute().getResultSet();
        assertEquals("19", result.getValue());
    }



}