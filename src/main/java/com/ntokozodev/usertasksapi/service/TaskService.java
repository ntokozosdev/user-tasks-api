package com.ntokozodev.usertasksapi.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.ntokozodev.usertasksapi.common.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ntokozodev.usertasksapi.exception.EntityNotFoundException;
import com.ntokozodev.usertasksapi.exception.ServiceException;
import com.ntokozodev.usertasksapi.model.db.Task;
import com.ntokozodev.usertasksapi.model.db.User;
import com.ntokozodev.usertasksapi.model.task.TaskDTO;
import com.ntokozodev.usertasksapi.repository.TaskRepository;
import com.ntokozodev.usertasksapi.repository.UserRepository;

@Service
public class TaskService {
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskService(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    public Task createTask(TaskDTO request, long userId)
            throws ServiceException, EntityNotFoundException, IllegalArgumentException {
        String infoMessage = "[createTask] creating task: { name: {}, description: {}, date_time: {} }";
        LOG.info(infoMessage, request.getName(), request.getDescription(), request.getDate_time());

        try {
            Optional<User> userEntity = userRepository.findById(userId);
            if (userEntity.isEmpty()) {
                throw new EntityNotFoundException(
                        String.format("Couldn't create task no user found for Id [%s]", userId));
            }

            Task task = new Task();
            task.setName(request.getName());
            task.setDescription(request.getDescription());
            task.setDate_time(parseDate(request.getDate_time()));
            task.setUser(userEntity.get());
            task.setStatus(Status.PENDING);

            return taskRepository.save(task);
        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException || ex instanceof IllegalArgumentException) {
                throw ex;
            }

            throw new ServiceException(String.format("Error creating task [%s]", request.getName()), ex);
        }
    }

    public Task updateUserTask(TaskDTO request, long userId, long taskId)
            throws ServiceException, EntityNotFoundException, IllegalArgumentException {
        LOG.info("[updateUser] updating task with userId: [{}], taskId: [{}]", userId, taskId);

        try {
            Optional<Task> taskEntity = taskRepository.findById(taskId);
            if (taskEntity.isEmpty()) {
                var message = String.format("Couldn't update task no task found for Id [%s]", taskId);
                throw new EntityNotFoundException(message);
            }

            var task = taskEntity.get();
            if (task.getUser().getId() != userId) {
                throw new ServiceException(String.format("Invalid user for given taskId: [%s]", taskId));
            }

            if (request.getName() != null) {
                task.setName(request.getName());
            }

            if (request.getDescription() != null) {
                task.setDescription(request.getDescription());
            }

            if (request.getDate_time() != null) {
                task.setDate_time(parseDate(request.getDate_time()));
            }

            return taskRepository.save(task);

        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException || ex instanceof IllegalArgumentException) {
                throw ex;
            }

            var message = String.format("Error updating task with userId [%s], taskId [%s]", userId, taskId);
            throw new ServiceException(message, ex);
        }
    }

    public Task getUserTask(long userId, long taskId)
            throws ServiceException, EntityNotFoundException, IllegalArgumentException {
        LOG.info("[getUserTask] retrieving task taskId: [{}] for userId: [{}]", taskId, userId);

        try {
            Optional<Task> taskEntity = taskRepository.findById(taskId);
            if (taskEntity.isEmpty()) {
                throw new EntityNotFoundException(String.format("No task found for Id [%s]", taskId));
            }

            var task = taskEntity.get();
            if (task.getUser().getId() != userId) {
                throw new IllegalArgumentException(String.format("Invalid user for given taskId: [%s]", taskId));
            }

            return task;

        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException || ex instanceof IllegalArgumentException) {
                throw ex;
            }

            var message = String.format("Error retrieving task with userId [%s] - taskId [%s]", userId, taskId);
            throw new ServiceException(message, ex);
        }
    }

    public List<Task> getUserTasks(long userId) throws ServiceException, EntityNotFoundException {
        LOG.info("[getUserTasks] request for Id: [{}]", userId);

        try {
            Optional<User> userEntity = userRepository.findById(userId);
            if (userEntity.isEmpty()) {
                String message = String.format("Couldn't retrieve tasks, no user found for Id [%s]", userId);
                throw new EntityNotFoundException(message);
            }

            return taskRepository.findByUser(userEntity.get());

        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException) {
                throw ex;
            }
            throw new ServiceException("Error retrieving all user tasks", ex);
        }
    }

    public Page<Task> getUserTasksPaginated(long userId, int page, int size)
            throws ServiceException, EntityNotFoundException {
        LOG.info("[getUserTasksPaginated] request: { userId: {}, page: {}, size: {} }", userId, page, size);

        try {
            Optional<User> userEntity = userRepository.findById(userId);
            if (userEntity.isEmpty()) {
                String message = String.format("Couldn't retrieve tasks, no user found for Id [%s]", userId);
                throw new EntityNotFoundException(message);
            }

            return taskRepository.findByUser(userEntity.get(), PageRequest.of(page, size, Sort.by("id")));

        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException) {
                throw ex;
            }
            throw new ServiceException("Error retrieving all user tasks", ex);
        }
    }

    public void deleteUserTask(long userId, long taskId)
            throws ServiceException, EntityNotFoundException, IllegalArgumentException {
        LOG.info("[deleteUserTask] deleting task taskId: [{}] for userId: [{}]", taskId, userId);

        try {
            Optional<Task> taskEntity = taskRepository.findById(taskId);
            if (taskEntity.isEmpty()) {
                throw new EntityNotFoundException(String.format("No task found for Id [%s]", taskId));
            }

            var task = taskEntity.get();
            if (task.getUser().getId() != userId) {
                throw new IllegalArgumentException(String.format("Invalid user for given taskId: [%s]", taskId));
            }

            taskRepository.delete(task);

        } catch (Exception ex) {
            if (ex instanceof EntityNotFoundException || ex instanceof IllegalArgumentException) {
                throw ex;
            }

            String message = String.format("Error deleting task with userId [%s] - taskId [%s]", userId, taskId);
            throw new ServiceException(message, ex);
        }
    }

    @Scheduled(cron = "0 */1 * ? * *")
    public void scheduleStatusUpdate() {
        var pendingTasks = taskRepository.findByStatus(Status.PENDING);
        LOG.info("[scheduleStatusUpdate] total pending tasks: [{}]", pendingTasks.size());
        List<Task> completed = pendingTasks
                .parallelStream()
                .filter(this::isTaskDatePassed)
                .map(this::completeTask)
                .collect(Collectors.toList());
        LOG.info("[scheduleStatusUpdate] total completed tasks: [{}]", completed.size());
        taskRepository.saveAll(completed);
    }

    private boolean isTaskDatePassed(Task task) {
        LOG.info("[isTaskDatePassed] checking task: [{}] date: [{}]", task.getName(), task.getDate_time());
        return task.getDate_time().isBefore(LocalDateTime.now());
    }

    private Task completeTask(Task task) {
        LOG.info("[completeTask] tasks: [{}], date: [{}] has passed", task.getName(), task.getDate_time());
        task.setStatus(Status.DONE);
        return task;
    }

    private LocalDateTime parseDate(String date) {
        try {
            LOG.info("[parseDate] parsing string date: [{}]", date);
            return LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ex) {
            throw new IllegalArgumentException(String.format("[parseDate] couldn't parse date string: [%s]", date), ex);
        }
    }
}
