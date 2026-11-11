# Exercise 1 – Servlets & Thymeleaf

**Goal:** Create a Spring Boot project and build two servlets: a greeting page and a simple category manager. Both render their HTML with Thymeleaf templates.

**You will learn:** how an HTTP request reaches a servlet, how to read request parameters, how to pass data to a template, and the POST-Redirect-GET pattern.

---

## 1. Creating the project

In IntelliJ IDEA choose **New Project → Spring Boot**.

![img.png](../images/img.png)

| Setting      | Value                 |
|--------------|-----------------------|
| Language     | Java                  |
| Type         | Maven                 |
| Group        | `mk.ukim.finki`       |
| Artifact     | `wp-2026`             |
| Package name | `mk.ukim.finki.wp2026` |
| JDK / Java   | 25                    |
| Packaging    | Jar                   |

![img_1.png](../images/img_1.png)

Select Spring Boot **4.1.1** and add the dependencies **Spring Web**, **Thymeleaf** and **Lombok**.

![img_2.png](../images/img_2.png)

---

## 2. Project structure

```
src/main/java/mk/ukim/finki/wp2026/
├── Wp2026Application.java          ← entry point
└── web/servlet/
    ├── HelloWorldServlet.java      ← /  and /hello
    └── CategoryServlet.java        ← /servlet/category
src/main/resources/
├── application.properties          ← configuration
└── templates/
    ├── hello.html                  ← Thymeleaf template
    └── categories.html             ← Thymeleaf template
```

---

## 3. Configuration

**`application.properties`**
```properties
spring.application.name=wp-2026
server.port=9090
```
The app runs on **http://localhost:9090** instead of the default port 8080.

**`Wp2026Application.java`**
```java
@SpringBootApplication
@ServletComponentScan
public class Wp2026Application { ... }
```
> ⚠️ **`@ServletComponentScan` is required.** Without it Spring Boot ignores classes annotated with `@WebServlet`, and every servlet URL returns 404.

---

## 4. How a request is handled

```
Browser ──GET /hello?name=Ana──▶ Tomcat ──▶ HelloWorldServlet.doGet()
                                                   │ reads parameters
                                                   │ puts data in WebContext
                                                   ▼
Browser ◀──────── HTML ──────── Thymeleaf renders hello.html
```

The **servlet** handles the logic and the **template** handles the presentation. We keep these separate on purpose.

---

## 5. HelloWorldServlet

```java
@WebServlet(name = "HelloWorldServlet", urlPatterns = {"", "/hello"})
public class HelloWorldServlet extends HttpServlet {
    private final SpringTemplateEngine springTemplateEngine;

    public HelloWorldServlet(SpringTemplateEngine springTemplateEngine) {
        this.springTemplateEngine = springTemplateEngine;   // injected by Spring
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        IWebExchange webExchange = JakartaServletWebApplication
                .buildApplication(getServletContext())
                .buildExchange(req, resp);
        WebContext context = new WebContext(webExchange);

        String name = req.getParameter("name");       // null if missing
        String surname = req.getParameter("surname");
        if (name == null) name = "X";
        if (surname == null) surname = "Y";

        context.setVariable("name", name);
        context.setVariable("surname", surname);

        springTemplateEngine.process("hello.html", context, resp.getWriter());
    }
}
```

**`templates/hello.html`**
```html
<h1 th:text="'Hello ' + ${name} + ' ' + ${surname}"></h1>
```

- `urlPatterns = {"", "/hello"}` maps the servlet to both `/` and `/hello`.
- `req.getParameter(...)` reads values from the query string (`?name=...`) or from form data.
- `context.setVariable("name", ...)` makes the value available in the template as `${name}`.

> 💡 **Why a template instead of `writer.format("<html>...")`?**
> Writing HTML as Java strings is hard to read and maintain. It is also **unsafe**: opening `/hello?name=<script>alert(1)</script>` would run that script in the browser. This attack is called XSS. Thymeleaf's `th:text` escapes HTML automatically, so the input is shown as plain text.

**Try it:** http://localhost:9090/hello?name=John&surname=Doe

---

## 6. CategoryServlet

### The model
```java
@Getter @Setter @AllArgsConstructor
class Category {
    private String name;
    private String description;
}
```
Lombok generates the boilerplate code at compile time:

| Annotation            | Generates                                         |
|-----------------------|---------------------------------------------------|
| `@Getter` / `@Setter` | getters / setters for all fields                  |
| `@AllArgsConstructor` | constructor with all fields                       |
| `@NoArgsConstructor`  | empty constructor                                 |
| `@Data`               | getters, setters, `toString`, `equals`, `hashCode` |

Thymeleaf reads `${category.name}` by calling `getName()`, so the getters are required.

### Lifecycle & in-memory storage
```java
@Override
public void init() {
    categories = new ArrayList<>();
    addCategory("Movies", "Movies category");
    addCategory("Books", "Books category");
}
```
- `init()` runs **once**, when the servlet is created.
- `doGet()` / `doPost()` run **on every request**.
- There is only **one servlet instance**, so all users share the same list. The data is lost when the app restarts. In later exercises we move this into a proper repository.

### GET – show the page
```java
context.setVariable("ipAddress", req.getRemoteAddr());
context.setVariable("userAgent", req.getHeader("user-agent"));
context.setVariable("categories", categories);
springTemplateEngine.process("categories.html", context, resp.getWriter());
```

### POST – add a category
```java
@Override
protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    addCategory(req.getParameter("name"), req.getParameter("description"));
    resp.sendRedirect("/servlet/category");
}
```
`addCategory` ignores empty input. After saving, the servlet **redirects** back to the GET page instead of rendering HTML directly.

> 💡 **POST-Redirect-GET:** If a POST request rendered the page directly, pressing F5 would submit the form again and create a duplicate. With a redirect, the browser's last request is a harmless GET.

---

## 7. Thymeleaf cheat sheet

Templates live in `src/main/resources/templates/` and declare `xmlns:th="http://www.thymeleaf.org"`.

| Syntax                                  | Meaning                                             | Example from `categories.html` |
|-----------------------------------------|-----------------------------------------------------|---------------------------------|
| `${...}`                                | read a variable set in the servlet                  | `${ipAddress}`                  |
| `th:text`                               | set the element's text (HTML-escaped)               | `th:text="'Hello ' + ${ipAddress}"` |
| `th:each`                               | loop over a list                                    | `th:each="category : ${categories}"` |
| `@{...}`                                | build a URL relative to the app                     | `th:href="@{/hello}"`           |
| `th:action` / `th:method`               | form target and HTTP method                         | `th:action="@{/servlet/category}" th:method="POST"` |
| `<th:block>`                            | a wrapper that leaves no tag in the output          | `<th:block th:text="..."/>`     |

The input `name` attributes in the form (`name="name"`, `name="description"`) must match the strings passed to `req.getParameter(...)` in `doPost()`.

---

## 8. Running & testing

1. Run `Wp2026Application`, or run `./mvnw spring-boot:run` (`mvnw.cmd` on Windows).
2. Open http://localhost:9090/hello?name=John&surname=Doe and you should see **Hello John Doe**.
3. Open http://localhost:9090/servlet/category and you should see your IP address, your browser info, and the Movies and Books categories.
4. Fill in the form and submit it. The new category appears in the list.

---

## 9. Key concepts

1. **Servlet lifecycle:** `init()` runs once, and `doGet()`/`doPost()` run on every request.
2. **URL mapping:** `@WebServlet(urlPatterns = ...)` together with `@ServletComponentScan`.
3. **Request parameters:** `req.getParameter()` reads both query-string and form data.
4. **Template engine:** the servlet prepares the data and Thymeleaf renders the HTML.
5. **POST-Redirect-GET:** prevents duplicate form submissions.
6. **Constructor injection:** Spring passes `SpringTemplateEngine` to the servlet's constructor.

---

## 10. Common problems

| Symptom                                               | Fix |
|-------------------------------------------------------|-----|
| 404 on `/hello` or `/servlet/category`                | Add `@ServletComponentScan` to the main class. |
| `Cannot compile ... configured for JVM target 5`      | Maven didn't import correctly. Open the Maven tool window and click **Reload All Maven Projects**. Check that *Project Structure → SDK* is set to JDK 25. |
| `PKIX path building failed` when downloading dependencies | Your antivirus is scanning HTTPS traffic (e.g. Avast Web Shield). Disable HTTPS scanning, or add `repo.maven.apache.org` as an exception. |
| `No implementation for ArtifactHandlerManager was bound` | In *Settings → Build Tools → Maven*, set **Maven home path** to *Bundled (Maven 3)*, or update IntelliJ. |
| `Port 9090 already in use`                            | Stop the previous run, or change `server.port`. |

---

## 11. Try it yourself

1. Add a link from `hello.html` to the categories page.
2. Show the **number** of categories on the page. Hint: `${categories.size()}`.
3. Ignore a new category if one with the same name already exists.
4. Create a `/servlet/category/delete` servlet that removes a category by name, then redirects back.
