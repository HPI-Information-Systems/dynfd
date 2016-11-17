package org.mp.naumann;

import org.mp.naumann.database.ConnectionException;
import org.mp.naumann.database.jdbc.JdbcDataConnector;
import org.mp.naumann.database.utils.PostgresConnection;
import org.mp.naumann.processor.BatchProcessor;
import org.mp.naumann.processor.SynchronousBatchProcessor;
import org.mp.naumann.processor.batch.source.CsvFileBatchSource;
import org.mp.naumann.processor.batch.source.StreamableBatchSource;
import org.mp.naumann.processor.handler.database.DatabaseBatchHandler;
import org.mp.naumann.processor.handler.database.PassThroughDatabaseBatchHandler;

public class Demo {

	private static final String DRIVER_NAME = "org.postresql.Driver";
	private static final String BATCH_FILE = null;
	private static final String TABLE = null;
	private static final int BATCH_SIZE = 10;
	private static final String SCHEMA = "public";

	public static void main(String[] args) throws ClassNotFoundException, ConnectionException {
		StreamableBatchSource batchSource = new CsvFileBatchSource(BATCH_FILE, SCHEMA, TABLE, BATCH_SIZE);
		JdbcDataConnector dataConnector = new JdbcDataConnector(DRIVER_NAME, PostgresConnection.getConnectionInfo());
		DatabaseBatchHandler databaseBatchHandler = new PassThroughDatabaseBatchHandler(dataConnector);
		BatchProcessor bp = new SynchronousBatchProcessor(batchSource, databaseBatchHandler);
		bp.addBatchHandler(null);
		batchSource.startStreaming();
	}

}