# Exercise 2 – Layered Architecture

**Goal:** Refactor the "everything in one servlet" code from Exercise 1 into separate layers, each with a single responsibility.

**You will learn:** the Model / Repository / Service / Web layers, how Spring creates objects (beans) and injects them, why we program to interfaces, and how to pass errors from the service layer back to the page.

---

## 1. Before vs After

In Exercise 1 a single servlet did everything:

| Responsibility        | Exercise 1 (`MessyCategoryServlet`) | Exercise 2                         |
|-----------------------|-------------------------------------|------------------------------------|
| Data model            | inner class `Category`              | `model/Category`                   |
| Storing data          | `List<Category>` field + `init()`   | `bootstrap/DataHolder`             |
| Reading/writing data  | directly on the list                | `repository/CategoryRepository`    |
| Validation & rules    | `addCategory()` in the servlet      | `service/CategoryService`          |
| HTTP request/response | servlet                             | `web/servlet/CategoryServlet`      |

The old version is kept for comparison at **`/messy/servlet/category`**. The refactored version runs at **`/servlet/category`**.

> ⚠️ Two servlets must never share the same URL pattern. That is why the messy servlet was moved to `/messy/...`.

---

## 2. The layers

```
 Browser
    │  HTTP
    ▼
┌──────────────────────┐
│  Web (servlet)       │  reads parameters, calls the service, renders a template
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Service             │  business rules & validation
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Repository          │  save / find / delete data
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  DataHolder          │  the actual storage (an in-memory list for now)
└──────────────────────┘
```

**Rule:** each layer only talks to the layer **directly below** it. The servlet never touches `DataHolder`, and the repository knows nothing about HTTP.

The benefit: when we replace the in-memory list with a real database later in the course, **only the repository layer changes**.

```
src/main/java/mk/ukim/finki/wp2026/
├── model/Category.java
├── bootstrap/DataHolder.java
├── repository/
│   ├── CategoryRepository.java                 ← interface
│   └── impl/InMemoryCategoryRepositoryImpl.java
├── service/
│   ├── CategoryService.java                    ← interface
│   └── impl/CategoryServiceImpl.java
└── web/servlet/
    ├── CategoryServlet.java                    ← /servlet/category
    └── MessyCategoryServlet.java               ← /messy/servlet/category (old version)
```

---

## 3. Model – `Category`

```java
@Data
@AllArgsConstructor
public class Category {
    private String name;
    private String description;
}
```
- The class is now in its own package (`model`) and can be used anywhere in the app.
- `@Data` = `@Getter` + `@Setter` + `toString()` + `equals()` + `hashCode()`.

---

## 4. Storage – `DataHolder`

```java
@Component
public class DataHolder {
    public static List<Category> categories = null;

    @PostConstruct
    public void init() {
        categories = new ArrayList<>();
        categories.add(new Category("Movies", "Movies Category"));
        categories.add(new Category("Books", "Books Category"));
    }
}
```
- `@Component` tells Spring to create and manage one instance of this class (a **bean**).
- `@PostConstruct` runs once after Spring creates the bean. It does the same job the servlet's `init()` did before.
- The data lives in memory only, so every restart begins again with Movies and Books.

---

## 5. Repository – data access

```java
public interface CategoryRepository {
    Category save(Category category);
    List<Category> findAll();
    Optional<Category> findByName(String name);
    List<Category> search(String text);
    void delete(String name);
}
```

```java
@Repository
public class InMemoryCategoryRepositoryImpl implements CategoryRepository {
    public Category save(Category category) {
        // same name = same category → replace it (prevents duplicates)
        DataHolder.categories.removeIf(c -> c.getName().equals(category.getName()));
        DataHolder.categories.add(category);
        return category;
    }

    public Optional<Category> findByName(String name) {
        return DataHolder.categories.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst();
    }

    public List<Category> search(String text) {
        return DataHolder.categories.stream()
                .filter(c -> c.getName().contains(text) || c.getDescription().contains(text))
                .toList();
    }
    // findAll(), delete() ...
}
```
- `@Repository` marks the class as a data-access bean.
- `Optional<Category>` makes "might not exist" explicit, so callers have to handle the empty case instead of getting a surprise `null`.
- The repository uses **Java Streams** (`stream().filter(...).toList()`) to query the list.
- For simplicity the **name** works as the identifier here. Later we'll switch to proper unique IDs.

> 💡 **Why an interface?** The service depends on `CategoryRepository`, not on `InMemoryCategoryRepositoryImpl`. Tomorrow we can write a `JpaCategoryRepository` and the service code won't change at all.

---

## 6. Service – business logic

```java
public interface CategoryService {
    List<Category> listCategories();
    Category create(String name, String description);
    Category update(String name, String description);
    void delete(String name);
    List<Category> searchCategories(String text);
}
```

```java
@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public Category create(String name, String description) {
        // Business rule: name and description are required
        if (name == null || name.isEmpty() || description == null || description.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return categoryRepository.save(new Category(name, description));
    }
    // other methods delegate to the repository
}
```
- `@Service` marks the class as a business-logic bean.
- Validation now lives **here**. Any client (a servlet today, a REST controller later) gets the same rules.
- Invalid input **throws an exception** instead of failing silently like in Exercise 1. The caller decides how to show the error.

---

## 7. Dependency injection – how the pieces connect

None of these classes ever calls `new` on another layer. Spring does the wiring:

```
Spring starts
 ├─ creates InMemoryCategoryRepositoryImpl   (@Repository)
 ├─ creates CategoryServiceImpl(repository)  (@Service, needs a CategoryRepository)
 └─ creates CategoryServlet(templateEngine, service)
```

When a constructor asks for an **interface** (`CategoryRepository`), Spring looks for the one bean that implements it and passes it in. This is called **constructor injection**.

---

## 8. Web – the refactored servlet

```java
@WebServlet(name = "CategoryServlet", urlPatterns = "/servlet/category")
public class CategoryServlet extends HttpServlet {
    private final SpringTemplateEngine springTemplateEngine;
    private final CategoryService categoryService;

    // constructor injection of both dependencies ...

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // ... build WebContext as in Exercise 1
        context.setVariable("categories", categoryService.listCategories());
        context.setVariable("errorMessage", req.getParameter("errorMessage"));
        springTemplateEngine.process("categories.html", context, resp.getWriter());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            categoryService.create(req.getParameter("name"), req.getParameter("description"));
        } catch (IllegalArgumentException e) {
            resp.sendRedirect("/servlet/category?errorMessage=Invalid input for category");
            return;
        }
        resp.sendRedirect("/servlet/category");
    }
}
```
Compared to the messy version, this servlet has **no list, no `init()` and no validation**. It only translates between HTTP and the service.

---

## 9. Error handling flow

```
POST (empty name) ─▶ servlet ─▶ service throws IllegalArgumentException
                        │ catch
                        ▼
   redirect to /servlet/category?errorMessage=Invalid input for category
                        ▼
   GET ─▶ errorMessage passed to template ─▶ shown in red
```

**`categories.html`**
```html
<div th:if="${errorMessage != null}">
    <p th:text="${errorMessage}" style="color: red;"></p>
</div>
```
- `th:if` renders the element only when the condition is true.
- The message goes through the URL because of POST-Redirect-GET (see Exercise 1). `th:text` escapes it, so a user can't inject HTML through `?errorMessage=...`.

---

## 10. Running & testing

1. Open http://localhost:9090/servlet/category and you should see Movies and Books.
2. Add a category with both fields filled in. It appears in the list.
3. Add a category with an empty field. The page shows *Invalid input for category*.
4. Add a category with an **existing name**. It replaces the old one instead of creating a duplicate.
5. Compare with the old version at http://localhost:9090/messy/servlet/category.

---

## 11. Key concepts

1. **Separation of concerns:** each layer has exactly one job.
2. **Layer rule:** Web → Service → Repository → Storage, never skipping a layer.
3. **Spring beans:** `@Component`, `@Repository` and `@Service` are classes that Spring creates and manages.
4. **Constructor injection:** dependencies come in through the constructor and are never created with `new`.
5. **Program to interfaces:** makes it easy to swap implementations later.
6. **Exceptions for errors:** the service throws and the web layer decides how to show the error.

---

## 12. Common problems

| Symptom | Fix |
|---------|-----|
| App behaves like the old version, or the error message never shows | Two servlets share the same `urlPatterns`. Every URL must be unique. |
| `No qualifying bean of type 'CategoryRepository'` | The implementation is missing `@Repository`/`@Service`, or it's outside the `mk.ukim.finki.wp2026` package. |
| `expected single matching bean but found 2` | Two classes implement the same interface. Keep only one as a bean for now. |
| `NullPointerException` in the repository | `DataHolder` isn't a `@Component`, so `@PostConstruct` never ran and `categories` is still `null`. |

---

## 13. Try it yourself

The service already has methods the UI doesn't use yet:

1. **Search:** add a search form (GET, parameter `text`) and show `searchCategories(text)` instead of the full list.
2. **Delete:** add a "Delete" button next to each category that POSTs to a new `/servlet/category/delete` servlet, which calls `categoryService.delete(name)`.
3. **Better errors:** create your own `InvalidCategoryArgumentsException` and show a more specific message, e.g. *"Name is required"*.
4. **Business rule:** make `create` throw an error if a category with the same name already exists. Hint: use `findByName`.
