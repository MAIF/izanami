package izanami.example.domains;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import org.springframework.stereotype.Component;

import javax.validation.constraints.NotNull;
import java.util.Collections;

@Component
public class TodoLists {

    private Map<String, TodoList> todoLists = HashMap.empty();


    public List<TodoList> list() {
        return todoLists.values().toList();
    }

    public Option<TodoList> get(String user) {
        return todoLists.get(user);
    }

    public TodoList save(TodoList todoList) {
        this.todoLists = this.todoLists.put(todoList.name, todoList);
        return todoList;
    }

    public void delete(String name) {
        this.todoLists = todoLists.remove(name);
    }

    public void deleteAll() {
        this.todoLists = HashMap.empty();
    }


    public static class Subscription {
        public String email;
    }

    public static class TodoList {

        @NotNull
        public String name;
        @NotNull
        public String user;

        public java.util.List<String> subscriptions = Collections.emptyList();

        public TodoList() {
        }

        public TodoList(String name, java.util.List<String> subscriptions) {
            this.name = name;
            this.subscriptions = subscriptions;
        }
    }

}
