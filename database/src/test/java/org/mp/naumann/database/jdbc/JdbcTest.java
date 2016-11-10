package org.mp.naumann.database.jdbc;

import java.io.File;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mp.naumann.database.DataConnector;

@SuppressWarnings("WeakerAccess")
public class JdbcTest {

    private static final String originalPath = "src/test/data/csv";
    private static final File originalDir = new File(originalPath);
    private static final String testPath = "src/test/data/csv_test";
    private static final File testDir = new File(testPath);
    protected static final String testTableName = "test";
    protected static final String schema = "";
    static DataConnector connector;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        FileUtils.deleteQuietly(testDir);
        FileUtils.copyDirectory(originalDir, testDir);
        connector = new JdbcDataConnector("org.relique.jdbc.csv.CsvDriver", "jdbc:relique:csv:" + testPath);
    }

    @AfterClass
    public static void tearDownOnce() {
        connector.disconnect();
    }

}
