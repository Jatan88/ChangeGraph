package com.changegraph.backend.workflow;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowEventPublisher {

    private final JsonMapper jsonMapper;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> runEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> projectEmitters = new ConcurrentHashMap<>();

    public WorkflowEventPublisher(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public SseEmitter subscribeRun(UUID runId, List<WorkflowResponses.WorkflowStreamPayload> replay) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        CopyOnWriteArrayList<SseEmitter> list = runEmitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        detachOnClose(list, emitter);
        for (WorkflowResponses.WorkflowStreamPayload payload : replay) {
            send(list, emitter, "workflow", payload);
        }
        return emitter;
    }

    public SseEmitter subscribeProject(UUID projectId) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        CopyOnWriteArrayList<SseEmitter> list = projectEmitters.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        detachOnClose(list, emitter);
        return emitter;
    }

    public void publish(WorkflowResponses.WorkflowStreamPayload payload) {
        CopyOnWriteArrayList<SseEmitter> list = runEmitters.get(payload.runId());
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(list, emitter, "workflow", payload);
        }
    }

    public void publishStatus(WorkflowRun run) {
        WorkflowResponses.WorkflowStatusPayload payload = WorkflowResponses.status(run);
        CopyOnWriteArrayList<SseEmitter> list = runEmitters.get(run.getId());
        if (list != null) {
            for (SseEmitter emitter : list) {
                send(list, emitter, "workflow-status", payload);
            }
        }
    }

    public void publishProject(UUID projectId, String change, WorkflowRun run) {
        WorkflowResponses.WorkflowProjectPayload payload = new WorkflowResponses.WorkflowProjectPayload(
                change,
                WorkflowResponses.summary(run));
        CopyOnWriteArrayList<SseEmitter> list = projectEmitters.get(projectId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(list, emitter, "workflow-project", payload);
        }
    }

    private void detachOnClose(CopyOnWriteArrayList<SseEmitter> list, SseEmitter emitter) {
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(error -> list.remove(emitter));
    }

    private void send(CopyOnWriteArrayList<SseEmitter> list, SseEmitter emitter, String name, Object payload) {
        try {
            String id = payload instanceof WorkflowResponses.WorkflowStreamPayload stream
                    ? String.valueOf(stream.sequence())
                    : null;
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name).data(jsonMapper.writeValueAsString(payload));
            if (id != null) {
                builder.id(id);
            }
            emitter.send(builder);
        } catch (IOException | RuntimeException ex) {
            list.remove(emitter);
            emitter.complete();
        }
    }
}
