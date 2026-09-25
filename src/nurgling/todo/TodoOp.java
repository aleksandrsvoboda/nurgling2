package nurgling.todo;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One edit waiting to reach the database. An edit is a function over the row, not a whole row: when
 * someone else wrote first, the same function is re-applied to their newer copy, so edits to different
 * fields both survive without keeping a merge baseline.
 */
final class TodoOp {
    /** The fields of a task an edit can touch, for spotting two people changing the same one. */
    enum Field {
        TITLE(i -> i.title), NOTES(i -> i.notes), ASSIGNEE(i -> i.assignee), URGENT(i -> i.urgent),
        ORDER(i -> i.order), LOC(i -> i.hasLoc ? i.locGrid + ":" + i.locX + ":" + i.locY : ""),
        REPEAT(i -> i.repeatH), DONE(i -> i.done + ":" + i.doneAt), LIST(i -> i.listId), DELETED(i -> i.deleted);

        final Function<TodoItem, Object> get;

        Field(Function<TodoItem, Object> get) {
            this.get = get;
        }
    }

    final boolean isList;
    /** Mutable: a create whose random id is already taken gets re-rolled. */
    int id;
    /** The full row to insert, or null for an edit of an existing row. */
    final TodoItem newItem;
    final TodoList newList;
    final Consumer<TodoItem> itemFn;
    final Consumer<TodoList> listFn;
    final Set<Field> fields;
    /** The task as this client saw it when the edit was made. */
    final TodoItem before;

    private TodoOp(boolean isList, int id, TodoItem newItem, TodoList newList, Consumer<TodoItem> itemFn,
                   Consumer<TodoList> listFn, Set<Field> fields, TodoItem before) {
        this.isList = isList;
        this.id = id;
        this.newItem = newItem;
        this.newList = newList;
        this.itemFn = itemFn;
        this.listFn = listFn;
        this.fields = fields;
        this.before = before;
    }

    static TodoOp createItem(TodoItem it) {
        return new TodoOp(false, it.id, it, null, null, null, EnumSet.noneOf(Field.class), null);
    }

    static TodoOp createList(TodoList l) {
        return new TodoOp(true, l.id, null, l, null, null, EnumSet.noneOf(Field.class), null);
    }

    static TodoOp editItem(int id, Set<Field> fields, Consumer<TodoItem> fn, TodoItem before) {
        return new TodoOp(false, id, null, null, fn, null, fields, before);
    }

    static TodoOp editList(int id, Consumer<TodoList> fn) {
        return new TodoOp(true, id, null, null, null, fn, EnumSet.noneOf(Field.class), null);
    }

    boolean isCreate() {
        return newItem != null || newList != null;
    }

    /** True when {@code remote} has a different value than we saw for a field this edit changes. */
    boolean collidesWith(TodoItem remote) {
        if (before == null || remote == null)
            return false;
        for (Field f : fields) {
            if (f != Field.DELETED && !Objects.equals(f.get.apply(before), f.get.apply(remote)))
                return true;
        }
        return false;
    }
}
