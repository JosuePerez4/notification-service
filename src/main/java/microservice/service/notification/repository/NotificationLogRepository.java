package microservice.service.notification.repository;

import microservice.service.notification.model.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByPaperId(UUID paperId);
}
