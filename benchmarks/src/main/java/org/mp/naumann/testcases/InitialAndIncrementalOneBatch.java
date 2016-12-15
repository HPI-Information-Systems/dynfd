package org.mp.naumann.testcases;

import org.mp.naumann.FileSource;
import org.mp.naumann.algorithms.benchmark.speed.BenchmarkLevel;
import org.mp.naumann.algorithms.benchmark.speed.SpeedBenchmark;
import org.mp.naumann.algorithms.benchmark.speed.SpeedEvent;
import org.mp.naumann.algorithms.benchmark.speed.SpeedEventListener;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFDVersion;
import org.mp.naumann.algorithms.fd.utils.IncrementalFDResultListener;
import org.mp.naumann.benchmarks.IncrementalFDBenchmark;
import org.mp.naumann.database.ConnectionException;
import org.mp.naumann.database.utils.ConnectionManager;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;

public class InitialAndIncrementalOneBatch implements TestCase, SpeedEventListener {


    private final long splitLine;
    private final int batchSize;
    private final String filename;
    private int stopAfter;
    private final IncrementalFDResultListener resultListener = new IncrementalFDResultListener();
    private SpeedEvent initialOverAll, initialForIncremental;
    private final LinkedList<SpeedEvent> batchEvents;

    private int status  = 0;

    private final IncrementalFDBenchmark benchmark;

    public InitialAndIncrementalOneBatch(long splitLine, int batchSize, String filename, IncrementalFDBenchmark benchmark, int stopAfter) {
        this.splitLine = splitLine;
        this.batchSize = batchSize;
        this.filename = filename;
        this.stopAfter = stopAfter;
        this.batchEvents = new LinkedList<>();
        this.benchmark = benchmark;
        SpeedBenchmark.addEventListener(this);
    }

    @Override
    public void execute() throws ConnectionException, IOException {

        FileSource fs = new FileSource(filename, (int)splitLine, batchSize);
        fs.doSplit();
        benchmark.setIncrementalFDResultListener(this.resultListener);
        benchmark.constructInitialOnly(
                "Warm up with an initial algorithm",
                ConnectionManager.getCsvConnectionFromAbsolutePath(FileSource.TEMP_DIR, ","),
                "benchmark",
                "baseline"
        );
        benchmark.runInitial();

        status = 1;
        benchmark.constructInitialOnly(IncrementalFDVersion.HYFD_ORIGINAL,
                "Initial algorithm for Baseline + 1 Batch",
                ConnectionManager.getCsvConnectionFromAbsolutePath(FileSource.TEMP_DIR, ","),
                "benchmark",
                "baselineandone");
        benchmark.runInitial();

        status = 2;
        benchmark.constructTestCase(
                "Initial and Incremental algorithm",
                FileSource.INSERTS_PATH,
                ConnectionManager.getCsvConnectionFromAbsolutePath(FileSource.TEMP_DIR, ","),
                "benchmark",
                "baseline",
                batchSize,
                stopAfter);
        benchmark.runInitial();
        status = 3;
        benchmark.runIncremental();
        fs.cleanup();
    }

    @Override
    public String sheetName() {
        return "Initial over all + Incremental One Batch";
    }

    @Override
    public Object[] sheetValues() {
        return new Object[]{
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                filename,
                benchmark.getVersionCode(),
                benchmark.getVersionName(),
                splitLine,
                batchSize,
                initialOverAll.getDuration(),
                initialForIncremental.getDuration(),
                batchEvents.peek().getDuration(),
                batchEvents.size(),
                getAverageTime(),
                getMedianTime(),
                resultListener.getValidationCount(),
                resultListener.getPrunedCount(),
                resultListener.getFDs().size()
        };
    }

    @Override
    public void receiveEvent(SpeedEvent info) {
        if(status == 1 && info.getLevel() == BenchmarkLevel.ALGORITHM)
            this.initialOverAll = info;
        else if (status == 2 && info.getLevel() == BenchmarkLevel.ALGORITHM) {
            this.initialForIncremental = info;
        }
        else if(status == 3 && info.getLevel() == BenchmarkLevel.BATCH)
            batchEvents.add(info);
    }

    private long getAverageTime(){
        return (long)batchEvents
                .stream()
                .mapToLong(SpeedEvent::getDuration)
                .average()
                .orElse(-1);
    }
    private long getMedianTime(){
        return batchEvents
                .stream()
                .mapToLong(SpeedEvent::getDuration)
                .sorted()
                .skip(batchEvents.size() / 2)
                .findFirst()
                .orElse(-1);
    }

}