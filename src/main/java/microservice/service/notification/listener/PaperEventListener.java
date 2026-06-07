package microservice.service.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import microservice.service.notification.dto.PaperEvaluatedEvent;
import microservice.service.notification.model.NotificationLog;
import microservice.service.notification.repository.NotificationLogRepository;
import microservice.service.notification.service.EmailService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaperEventListener {

    private final NotificationLogRepository repository;
    private final EmailService emailService;

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

        if (data.authors() != null && !data.authors().isEmpty()) {
            for (PaperEvaluatedEvent.Author author : data.authors()) {
                String status = sendNotification(author.email(), subject, content);

                NotificationLog logEntry = NotificationLog.builder()
                        .paperId(data.paperId())
                        .conferenceId(data.conferenceId())
                        .recipientEmail(author.email())
                        .subject(subject)
                        .content(content)
                        .sentAt(LocalDateTime.now())
                        .status(status)
                        .build();

                repository.save(logEntry);
                log.info("Notification log persisted for author: {} with status: {}", author.email(), status);
            }
        } else {
            log.warn("No authors found for paperId: {}", data.paperId());
        }
    }

    private String sendNotification(String recipientEmail, String subject, String content) {
        try {
            emailService.sendEmail(recipientEmail, subject, content);
            return "SENT";
        } catch (Exception e) {
            log.error("Failed to send email to {}", recipientEmail, e);
            return "FAILED";
        }
    }
}
