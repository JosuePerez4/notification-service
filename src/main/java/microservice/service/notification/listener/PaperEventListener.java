package microservice.service.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import microservice.service.notification.config.RabbitMQConfig;
import microservice.service.notification.dto.PaperEvaluatedEvent;
import microservice.service.notification.model.NotificationLog;
import microservice.service.notification.repository.NotificationLogRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaperEventListener {

    private final NotificationLogRepository repository;

    @RabbitListener(queues = "${RABBITMQ_QUEUE_NOTIFICATION}")
    public void handlePaperEvaluated(PaperEvaluatedEvent event) {
        if (event == null || event.data() == null) {
            log.warn("Received null event or data");
            return;
        }

        PaperEvaluatedEvent.Data data = event.data();
        log.info("Received paper.evaluated event for paperId: {}", data.paperId());

        String subject = "Evaluation Result for your Paper: " + data.title();
        String content = String.format("Your paper titled '%s' (ID %s) has been %s. Observations: %s",
                data.title(), data.paperId(), data.status(), data.evaluationObservations());

        // Iterate over authors to send/log notifications
        if (data.authors() != null && !data.authors().isEmpty()) {
            for (PaperEvaluatedEvent.Author author : data.authors()) {
                NotificationLog logEntry = NotificationLog.builder()
                        .paperId(data.paperId())
                        .conferenceId(data.conferenceId())
                        .recipientEmail(author.email())
                        .subject(subject)
                        .content(content)
                        .sentAt(LocalDateTime.now())
                        .status("SENT")
                        .build();

                repository.save(logEntry);
                log.info("Notification log persisted for author: {}", author.email());
            }
        } else {
            log.warn("No authors found for paperId: {}", data.paperId());
        }
    }
}
