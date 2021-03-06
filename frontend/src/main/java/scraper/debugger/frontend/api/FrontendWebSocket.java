package scraper.debugger.frontend.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import scraper.debugger.dto.*;

import java.net.URI;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;

public abstract class FrontendWebSocket extends WebSocketClient {

    // Query response queue
    private final BlockingQueue<Deque<DataflowDTO>> queryQueue = new SynchronousQueue<>(true);

    // Query response bringing thread
    private final ExecutorService queryBringer = Executors.newSingleThreadExecutor();

    private final ObjectMapper m = new ObjectMapper();

    public FrontendWebSocket(String bindingIp, int port) {
        super(URI.create("ws://" + bindingIp + ":" + port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
    }

    @Override
    public void onMessage(String msg) {
        try {
            Map<String, Object> data = m.readValue(msg, Map.class);
            String type = (String) data.get("type");
            switch (type) {
                case "specification": {
                    Map<String, String> dto = (Map<String, String>) data.get("data");
                    InstanceDTO ins = m.readValue(dto.get("instance"), InstanceDTO.class);
                    ControlFlowGraphDTO cfg = m.readValue(dto.get("cfg"), ControlFlowGraphDTO.class);
                    takeSpecification(ins, cfg);
                    return;
                }
                case "identifiedFlow": {
                    Map<String, String> dto = (Map<String, String>) data.get("data");
                    DataflowDTO f = m.readValue(dto.get("flow"), DataflowDTO.class);
                    takeIdentifiedFlow(f);
                    return;
                }
                case "breakpointHit": {
                    Map<String, String> dto = (Map<String, String>) data.get("data");
                    DataflowDTO f = m.readValue(dto.get("flow"), DataflowDTO.class);
                    takeBreakpointHit(f);
                    return;
                }
                case "finishedFlow": {
                    Map<String, String> dto = (Map<String, String>) data.get("data");
                    DataflowDTO f = m.readValue(dto.get("flow"), DataflowDTO.class);
                    takeFinishedFlow(f);
                    return;
                }
                case "log": {
                    String log = (String) data.get("data");
                    takeLogMessage(log);
                    return;
                }
                case "flowLifecycle": {
                    queryBringer.execute(() -> {
                        try {
                            Deque<String> dto = (Deque<String>) data.get("data");
                            Deque<DataflowDTO> converted = new LinkedList<>();
                            for (String str : dto) {
                                converted.add(m.readValue(str, DataflowDTO.class));
                            }
                            queryQueue.add(converted);
                        } catch (JsonProcessingException e) {
                            System.getLogger("Frontend").log(System.Logger.Level.WARNING, "Query error");
                            e.printStackTrace();
                            queryQueue.add(new LinkedList<>());
                        }
                    });
                }
            }

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int i, String s, boolean b) {
    }

    @Override
    public void onError(Exception e) {
    }


    //=================
    // Update Taking
    //=================

    protected abstract void takeSpecification(InstanceDTO jobIns, ControlFlowGraphDTO jobCFG);

    protected abstract void takeIdentifiedFlow(DataflowDTO f);

    protected abstract void takeBreakpointHit(DataflowDTO f);

    protected abstract void takeFinishedFlow(DataflowDTO f);

    protected abstract void takeLogMessage(String log);

    BlockingQueue<Deque<DataflowDTO>> getQueryQueue() {
        return queryQueue;
    }
}
