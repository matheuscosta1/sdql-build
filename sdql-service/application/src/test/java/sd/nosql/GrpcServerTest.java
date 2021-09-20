package sd.nosql;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sd.nosql.prototype.Record;
import sd.nosql.prototype.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/***
 =======================================================================================================================
 Due to the nature of Raft Protocol, in order to be able to rerun the rest successfully,
 always empty or remove the folder that stores the logs from Raft.
 Also, is needed to have raft running on background.
 =======================================================================================================================
 ***/
public class GrpcServerTest {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServerTest.class);
    private ManagedChannel channel;
    private DatabaseServiceGrpc.DatabaseServiceBlockingStub blockingStub;
    private DatabaseServiceGrpc.DatabaseServiceFutureStub asyncStub;
    private static GrpcServer grpcServer;

    /***
     * The tests order execution matter, so, in order to run the test correctly,
     * run each in order of appearance in the code
     */

    @Test
    void shouldWriteParallelWithMultipleClients() {
        List<Client> managedChannelCircular = IntStream.range(0, 5).mapToObj(i -> new Client(i, ManagedChannelBuilder.forAddress("localhost", 8080)
                .usePlaintext()
                .build())).collect(Collectors.toList());
        LongStream.range(0L, 1000L).parallel().forEach(number -> {
            Client client = managedChannelCircular.get((int) number % 5);
            RecordResult resultInsert = DatabaseServiceGrpc.newBlockingStub(client.getManagedChannel()).set(RecordInput.newBuilder()
                    .setKey(number)
                    .setRecord(Record.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .setData(ByteString.copyFrom(String.format("{\"message\": \" To every dream that I left behind....counting\", \"time\": %d }", number), StandardCharsets.UTF_8))
                            .build())
                    .build());
            client.upCount();
            assert resultInsert.getResultType().equals(ResultType.SUCCESS);
        });
    }

    @Test
    void shouldNotWriteSuccessfully() {
        LongStream.range(0L, 1000L).parallel().forEach(number -> {
            RecordResult resultInsert = blockingStub.set(RecordInput.newBuilder()
                    .setKey(number)
                    .setRecord(Record.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .setData(ByteString.copyFrom(String.format("{\"message\": \" To every dream that I left behind....counting\", \"time\": %d }", number), StandardCharsets.UTF_8))
                            .build())
                    .build());
            assert resultInsert.getResultType().equals(ResultType.ERROR);
        });
    }

    @Test
    void shouldWriteParallelWithAsyncStub() throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        List<Boolean> allSuccess = new ArrayList<>();
        LongStream.range(7000L, 8000L).parallel().forEach(number -> {
            var asyncResult = asyncStub.set(RecordInput.newBuilder()
                    .setKey(number)
                    .setRecord(Record.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .setData(ByteString.copyFrom(String.format("{\"message\": \" To every dream that I left behind....counting\", \"time\": %d }", number), StandardCharsets.UTF_8))
                            .build())
                    .build());
            try {
                Thread.sleep(120); // Sleep minimo para nao ocorrer a chance do GET sair do Client antes do SET
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            var asyncGet = asyncStub.get(Key.newBuilder()
                    .setKey(number)
                    .build());
            Futures.addCallback(asyncGet, new FutureCallback<>() {
                @Override
                public void onSuccess(RecordResult recordResult) {
                    count.getAndIncrement();
                    allSuccess.add(recordResult.getResultType().equals(ResultType.SUCCESS));
                }

                @Override
                public void onFailure(Throwable throwable) {
                    count.getAndIncrement();
                }
            }, MoreExecutors.directExecutor());
        });
        while (count.get() < 1000) {
            logger.info("Count: {}", count.get());
            Thread.sleep(1000);
        }
        logger.info("Total: {}, Result true: {}",allSuccess.size(), allSuccess.stream().filter(it -> it).count());
        assert allSuccess.stream().allMatch(it -> it);
    }

    @Test
    void shouldUpdateAllInSequence() {
        LongStream.range(2000L, 3000L).parallel().forEach(number -> {
            RecordResult result = blockingStub.testAndSet(RecordUpdate.newBuilder()
                    .setOldVersion(1)
                    .setKey(number)
                    .setRecord(Record.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .setData(ByteString.copyFrom(String.format("{\"message\": \" To every dream that I left behind new version....counting\", \"time\": %d }", number), StandardCharsets.UTF_8))
                            .build())
                    .build());
            RecordResult fetchedResult = blockingStub.get(Key.newBuilder().setKey(number).build());
            assert result.getResultType().equals(ResultType.SUCCESS);
            assert fetchedResult.getRecord().getVersion() == 2;
            assert fetchedResult.getRecord().getData().equals(ByteString.copyFrom(String.format("{\"message\": \" To every dream that I left behind new version....counting\", \"time\": %d }", number), StandardCharsets.UTF_8));
        });
    }

    @Test
    void shouldReadAllInSequence() {
        LongStream.range(0L, 1000L).parallel().forEach(number -> {
            RecordResult result = blockingStub.get(Key.newBuilder().setKey(number).build());
            if (number % 1000 == 0) logger.info("Result: {}", result);
            assert result.getResultType().equals(ResultType.SUCCESS);
        });
        LongStream.range(2000L, 3000L).parallel().forEach(number -> {
            RecordResult result = blockingStub.get(Key.newBuilder().setKey(number).build());
            if (number % 1000 == 0) logger.info("Result: {}", result);
            assert result.getResultType().equals(ResultType.SUCCESS);
        });
    }

    @BeforeAll
    static void startServer() throws InterruptedException {
        grpcServer = new GrpcServer(8080);
        AtomicBoolean started = new AtomicBoolean(false);
        new Thread(() -> {
            try {
                logger.info("GRPC server starting...");
                started.set(true);
                grpcServer.start();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
        while (!started.get()) Thread.sleep(1000);
    }

    @BeforeEach
    void startBlockingClient() {
        channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                .usePlaintext()
                .build();

        logger.info("Channel: {}", channel);
        blockingStub = DatabaseServiceGrpc.newBlockingStub(channel);
        asyncStub = DatabaseServiceGrpc.newFutureStub(channel);
    }

    static class Client {
        private final int id;
        private final ManagedChannel managedChannel;
        private int count;

        public Client(int id, ManagedChannel managedChannel) {
            this.id = id;
            this.managedChannel = managedChannel;
        }

        public ManagedChannel getManagedChannel() {
            return managedChannel;
        }

        public void upCount() {
            this.count++;
        }
    }
}
