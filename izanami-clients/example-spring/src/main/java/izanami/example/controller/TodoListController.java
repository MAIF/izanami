package izanami.example.controller;

import izanami.example.domains.TodoLists;
import izanami.example.domains.Items;
import izanami.example.notifications.NotificationService;
import izanami.javadsl.FeatureClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.text.MessageFormat;
import java.util.List;

@RestController
@RequestMapping("/api/todolists")
public class TodoListController {
    private final static Logger LOGGER = LoggerFactory.getLogger(TodoListController.class);

    private final TodoLists todoLists;
    private final Items items;
    private final FeatureClient featureClient;
    private final NotificationService notificationService;

    @Autowired
    public TodoListController(TodoLists todoLists, Items items, FeatureClient featureClient, NotificationService notificationService) {
        this.todoLists = todoLists;
        this.items = items;
        this.featureClient = featureClient;
        this.notificationService = notificationService;
        this.featureClient.onFeatureChanged("izanami:example:deleteAll", feature -> {
            if (feature.enabled()) {
                LOGGER.info("Delete all enabled");
            } else {
                LOGGER.info("Delete all disabled");
            }
        });
    }

    @GetMapping()
    public ResponseEntity<List<TodoLists.TodoList>> list() {
        return ResponseEntity.ok(this.todoLists.list().toJavaList());
    }

    @PostMapping()
    public ResponseEntity<TodoLists.TodoList> create(@RequestBody @Valid TodoLists.TodoList todo) {
        this.todoLists.save(todo);
        return new ResponseEntity<>(todo, HttpStatus.CREATED);
    }

    @GetMapping("/{name}")
    public ResponseEntity<TodoLists.TodoList> get(@PathVariable("name") String name) {
        return this.todoLists.get(name)
                .map(ResponseEntity::ok)
                .getOrElse(ResponseEntity.notFound()::build);
    }

    @PutMapping("/{name}")
    public ResponseEntity<TodoLists.TodoList> update(@PathVariable("name") String name, @RequestBody @Valid TodoLists.TodoList todo) {
        this.todoLists.delete(name);
        this.todoLists.save(todo);
        return ResponseEntity.ok(todo);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity delete(@PathVariable("name") String name) {
        this.todoLists.delete(name);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping()
    public ResponseEntity deleteAll() {
        todoLists.deleteAll();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/items")
    public ResponseEntity<List<Items.Item>> saveItem(@PathVariable("name") String name) {
        return ResponseEntity.ok(this.items.listItems(name).toJavaList());
    }

    @PostMapping("/{name}/items")
    public ResponseEntity<Items.Item> saveItem(@PathVariable("name") String name, @RequestBody @Valid Items.Item item) {
        this.todoLists.get(name).forEach(l -> {
            if (l.subscriptions != null) {
                String message = MessageFormat.format("The item {0} was added to list {1}", item.name, name);
                notificationService.sendNotification(l.subscriptions, message);
            }
        });
        return new ResponseEntity<>(this.items.saveItem(name, item), HttpStatus.CREATED);
    }


    @DeleteMapping("/{name}/items")
    public ResponseEntity deleteTodos(@PathVariable("name") String name, @RequestParam("done")  Boolean done) {
        return featureClient.featureOrElse(
                "izanami:example:deleteAll",
                () -> {
                    this.todoLists.get(name).forEach(l -> {
                        if (l.subscriptions != null) {
                            String message = MessageFormat.format("The list {0} was deleted", name);
                            notificationService.sendNotification(l.subscriptions, message);
                        }
                    });
                    this.items.deleteAllItems(name, done);
                    return ResponseEntity.noContent().build();
                },
                () ->
                        ResponseEntity.badRequest().build()
        ).get();
    }

    @GetMapping("/{name}/items/{itemName}")
    public ResponseEntity<Items.Item> getTodo(@PathVariable("name") String name, @PathVariable("itemName") String itemName) {
        return this.items.getItem(name, itemName)
                .map(ResponseEntity::ok)
                .getOrElse(ResponseEntity.notFound()::build);
    }

    @PutMapping("/{name}/items/{itemName}")
    public ResponseEntity<Items.Item> updateTodo(@PathVariable("name") String name, @PathVariable("itemName")String itemName, @RequestBody @Valid Items.Item item) {
        this.items.deleteItem(name, itemName);
        this.todoLists.get(name).forEach(l -> {
            if (l.subscriptions != null) {
                String message = MessageFormat.format("The item {0} was updated in list {1}", itemName, name);
                notificationService.sendNotification(l.subscriptions, message);
            }
        });
        return new ResponseEntity<>(this.items.saveItem(name, item), HttpStatus.CREATED);
    }

    @DeleteMapping("/{name}/items/{itemName}")
    public ResponseEntity deleteTodo(@PathVariable("name") String name, @PathVariable("itemName") String itemName) {
        this.items.deleteItem(name, itemName);
        this.todoLists.get(name).forEach(l -> {
            if (l.subscriptions != null) {
                String message = MessageFormat.format("The item {0} was deleted from list {1}", itemName, name);
                notificationService.sendNotification(l.subscriptions, message);
            }
        });
        return ResponseEntity.noContent().build();
    }


}
