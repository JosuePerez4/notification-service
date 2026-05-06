package microservice.service.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaperEvaluatedEvent(
        String eventType,          // "paper.evaluated"
        String eventVersion,       // "1.0"
        UUID eventId,
        Instant occurredAt,
        String source,             // "paper-service"
        Data data
) {
    public record Data(
            UUID paperId,
            UUID conferenceId,
            String title,
            String topic,
            String status,                 // ACCEPTED, REJECTED, IN_CORRECTIONS...
            String evaluationObservations,
            EvaluatedBy evaluatedBy,
            List<Author> authors
    ) {}

    public record EvaluatedBy(
            UUID userId,
            String role                    // CHAIR, ADMIN...
    ) {}

    public record Author(
            String name,
            String email
    ) {}
}
