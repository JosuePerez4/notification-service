package microservice.service.notification.controller;

import lombok.RequiredArgsConstructor;
import microservice.service.notification.model.NotificationLog;
import microservice.service.notification.repository.NotificationLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationLogRepository repository;

    @GetMapping("/paper/{paperId}")
    public List<NotificationLog> getNotificationsByPaper(@PathVariable UUID paperId) {
        return repository.findByPaperId(paperId);
    }
}
