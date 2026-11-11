# Exercise 3 – Sessions, Filters & Listeners

**Goal:** Add login and logout, protect every page with a filter so only logged-in users can see it, and count page views across the whole application.

**You will learn:** how the HTTP session remembers a user between requests, how a filter runs *before* every servlet, how a listener reacts to the application starting and stopping, and the difference between **request**, **session** and **application** scope.

---

## 1. What's new

| File | Purpose |
|------|---------|
| `model/User.java` | the user model |
| `model/exceptions/*` | our own exceptions for login/register errors |
| `repository/UserRepository.java` + `impl/InMemoryUserRepositoryImpl.java` | user data access |
| `service/AuthService.java` + `impl/AuthServiceImpl.java` | login & register rules |
| `web/servlet/LoginServlet.java` | `/login`: shows the form, logs the user in |
| `web/servlet/LogoutServlet.java` | `/logout`: ends the session |
| `web/filter/LoginFilter.java` | redirects to `/login` if the user is not logged in |
| `web/listener/ApplicationContextListener.java` | sets up the page-view counter when the app starts |
| `templates/login.html` | login form |

The user part follows exactly the same layers as categories in Exercise 2: **model → repository → service → servlet**.

---

## 2. Three scopes: where can data live?

HTTP is **stateless**: each request knows nothing about the previous one. To remember things, we store data in one of three places:

| Scope | Lives for | Shared between | How to use it | Used in this exercise for |
|-------|-----------|----------------|---------------|---------------------------|
| **Request** | one request | nobody | `req.getParameter(...)`, `context.setVariable(...)` | form data, `errorMessage` |
| **Session** | one user's visit (until logout or timeout) | all requests of **one** user | `req.getSession().setAttribute(...)` | the logged-in `user` |
| **Application** (`ServletContext`) | the whole time the app is running | **all** users | `getServletContext().setAttribute(...)` | `userViews` counter |

> 💡 **How does the server know which session is yours?** On the first request Tomcat creates a session and sends the browser a cookie called `JSESSIONID`. The browser sends that cookie back with every request, and Tomcat uses it to find your session. Open DevTools → Application → Cookies to see it.

---

## 3. User model & test data

```java
@Data
@AllArgsConstructor
public class User {
    private String username;   // used as the identifier
    private String password;
    private String name;
    private String surname;
}
```

`DataHolder` now also fills a `users` list on startup:

| Username | Password |
|----------|----------|
| `elena.atanasoska` | `ea` |
| `darko.sasanski` | `ds` |
| `bojana.marojevikj` | `bm` |

> ⚠️ Passwords are stored as **plain text** to keep the example simple. Real applications always store a **hash** of the password. We'll do that later with Spring Security.

---

## 4. Repository & service

```java
public interface UserRepository {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameAndPassword(String username, String password);
    User save(User user);
    void deleteByUsername(String username);
}
```
The in-memory implementation uses streams on `DataHolder.users`, the same way as `InMemoryCategoryRepositoryImpl`.

```java
@Service
public class AuthServiceImpl implements AuthService {
    // ... constructor injection of UserRepository

    @Override
    public User login(String username, String password) {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new InvalidArgumentsException();
        }
        return userRepository.findByUsernameAndPassword(username, password)
                .orElseThrow(InvalidUserCredentialsException::new);
    }

    @Override
    public User register(String username, String password, String repeatPassword, String name, String surname) {
        // all fields required → InvalidArgumentsException
        // password != repeatPassword → PasswordsDoNotMatchException
        return userRepository.save(new User(username, password, name, surname));
    }
}
```
- `orElseThrow(...)` means: return the user if one was found, otherwise throw the exception.
- `register` is ready in the service, but there is no page for it yet. See **Try it yourself**.

### Our own exceptions

```java
public class InvalidUserCredentialsException extends RuntimeException {
    public InvalidUserCredentialsException() {
        super("Invalid username or password.");
    }
}
```

| Exception | Thrown when | Message |
|-----------|-------------|---------|
| `InvalidArgumentsException` | a required field is empty | *Please fill in all required fields.* |
| `InvalidUserCredentialsException` | wrong username or password | *Invalid username or password.* |
| `PasswordsDoNotMatchException` | `password` ≠ `repeatPassword` | *Passwords do not match.* |

Why our own exceptions instead of `IllegalArgumentException`?
- The **name** tells you what went wrong.
- The servlet can catch **exactly** the errors it expects.
- The **message** is written for the user, so the servlet can show `e.getMessage()` as-is.

---

## 5. LoginServlet – starting a session

```java
@WebServlet(name = "loginServlet", urlPatterns = "/login")
public class LoginServlet extends HttpServlet {

    @Override
    protected void doGet(...) {
        // show the empty login form
        templateEngine.process("login.html", context, resp.getWriter());
    }

    @Override
    protected void doPost(...) {
        User user;
        try {
            user = authService.login(req.getParameter("username"), req.getParameter("password"));
        } catch (InvalidArgumentsException | InvalidUserCredentialsException e) {
            context.setVariable("errorMessage", e.getMessage());
            templateEngine.process("login.html", context, resp.getWriter());   // show the form again
            return;
        }

        req.getSession().setAttribute("user", user);   // ← the user is now "logged in"
        resp.sendRedirect("/servlet/category");
    }
}
```

- `catch (A | B e)` is a **multi-catch**: one block handles both exception types.
- We catch **only** the exceptions we expect. If we caught `RuntimeException`, a real bug (e.g. a `NullPointerException`) would show up on the page as if it were a wrong password.
- "Logged in" simply means: **the session has a `user` attribute**.

> 💡 **Two ways to show an error.** `CategoryServlet` *redirects* with `?errorMessage=...` (POST-Redirect-GET, see Exercise 1). `LoginServlet` *renders the page again* directly. Both are fine:
> - **Redirect** after a POST that may change data, so F5 can't submit it twice.
> - **Re-render** when the POST failed and nothing changed, as with a wrong password.

---

## 6. LogoutServlet – ending a session

```java
@WebServlet(name = "LogoutServlet", urlPatterns = "/logout")
public class LogoutServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.getSession().invalidate();   // removes the session and everything in it
        resp.sendRedirect("/login");
    }
}
```

---

## 7. Using the session in templates

Thymeleaf gives every template direct access to the session as `${session}`. The servlet doesn't need to pass anything.

**`categories.html`**
```html
<th:block th:if="${session.user}">
    <h2>Info about user</h2>
    <div th:text="${session.user.username}"></div>
    <div th:text="${session.user.name}"></div>
    <div th:text="${session.user.surname}"></div>
    <a th:href="@{/logout}">Logout</a>
</th:block>
```

---

## 8. LoginFilter – protecting every page

Without a filter, we would have to add an "is the user logged in?" check to **every** servlet. A **filter** runs **before** the servlet, so the check lives in one place.

```
Browser ──▶ LoginFilter ──▶ Servlet
               │
               ├─ user in session?        → continue  (filterChain.doFilter)
               ├─ path is ignored (/login)? → continue
               └─ otherwise               → redirect to /login
```

### Version 1 – the simplest filter
```java
@WebFilter
public class LoginFilter implements Filter {
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        User loggedInUser = (User) req.getSession().getAttribute("user");
        String path = req.getServletPath();

        if (loggedInUser == null && !path.equals("/login")) {
            resp.sendRedirect("/login");
        } else {
            filterChain.doFilter(req, resp);   // let the request continue to the servlet
        }
    }
}
```
- `filterChain.doFilter(...)` passes the request on. If you don't call it, the request **stops here**.
- `/login` must be allowed through. Otherwise `/login` redirects to `/login`, which redirects to `/login`, and so on, and the browser shows *"too many redirects"*.
- A bare `@WebFilter` with no URL works **only because Spring Boot** maps it to `/*` by default. In a regular servlet container it would match nothing. That's why version 2 writes the URL out.

### Version 2 – configurable filter
```java
@WebFilter(filterName = "auth-filter", urlPatterns = "/*",
        dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.FORWARD},
        initParams = @WebInitParam(name = "ignore-paths", value = "/login")
)
public class LoginFilter implements Filter {
    private List<String> ignorePaths;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // "/login, /register" -> ["/login", "/register"]
        this.ignorePaths = Arrays.stream(filterConfig.getInitParameter("ignore-paths").split(","))
                .map(String::trim)
                .toList();
    }

    // "/css" also covers "/css/style.css", but "/loginX" does NOT match "/login"
    private boolean isIgnored(String path) {
        return this.ignorePaths.stream()
                .anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    public void doFilter(...) {
        // ...
        if (loggedInUser == null && !isIgnored(path)) {
            resp.sendRedirect("/login");
        } else {
            filterChain.doFilter(req, resp);
        }
    }
}
```

| Setting | Meaning |
|---------|---------|
| `urlPatterns = "/*"` | run for every URL |
| `dispatcherTypes = {REQUEST, FORWARD}` | run for normal requests and for internal forwards |
| `initParams = @WebInitParam(...)` | configuration the filter reads once, in `init()` |

To allow more public pages, just change the value: `value = "/login, /register, /css"`. No new fields or `if`s are needed.

> ⚠️ **Watch the direction of `startsWith`.** An earlier version used `ignorePath.startsWith(path)`, which asks *"does `/login` start with the requested path?"*. For the home page `/`, the servlet path is `""`, and `"/login".startsWith("")` is **always true**. So the home page was reachable **without logging in**. The correct check is `path.startsWith(ignorePath)`. Bugs like this don't crash anything, so always test the pages that *should* be blocked.

---

## 9. Listener & ServletContext – a global page counter

A **listener** reacts to events in the application. `ServletContextListener` is called when the app **starts** and when it **stops**.

```java
@WebListener
public class ApplicationContextListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        sce.getServletContext().setAttribute("userViews", 0);   // app started
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        sce.getServletContext().removeAttribute("userViews");   // app stopping
    }
}
```

**`CategoryServlet.doGet()`**
```java
Integer userViews = (Integer) getServletContext().getAttribute("userViews");
userViews = userViews + 1;
getServletContext().setAttribute("userViews", userViews);
context.setVariable("userViews", userViews);
```

**`categories.html`**
```html
<div th:text="${userViews?:0}"></div>
```
- The `ServletContext` is shared by **everyone**. Log in as two different users, in two different browsers, and both see the same counter going up.
- `?:` is the "Elvis" operator: use `userViews`, or `0` if it is `null`.

> 💡 **What if two users load the page at the same moment?** Both can read `5` and both write `6`, so one view is lost. It's acceptable for a demo. A real app would use `AtomicInteger` or `synchronized`.

---

## 10. Running & testing

1. Open http://localhost:9090/servlet/category. You are redirected to `/login`.
2. Log in with a wrong password. You see *Invalid username or password.*
3. Log in as `darko.sasanski` / `ds`. You land on the categories page and see your name.
4. Refresh a few times. The view counter goes up.
5. Open a **private window** and log in as another user. Your name is different, but the counter is shared.
6. Click **Logout**, then try `/servlet/category` again. You are redirected to `/login`.
7. Try http://localhost:9090/ without logging in. It must also redirect.

---

## 11. Key concepts

1. **HTTP is stateless.** The session (via the `JSESSIONID` cookie) remembers a user between requests.
2. **Three scopes:** request (one request), session (one user), application (everyone).
3. **Logged in = a `user` attribute in the session.** Logging out = `session.invalidate()`.
4. **Filters** run before servlets and are the one place for cross-cutting checks like authentication.
5. **Listeners** react to application events like startup and shutdown.
6. **Our own exceptions** describe business errors and carry user-friendly messages.

---

## 12. Common problems

| Symptom | Fix |
|---------|-----|
| Browser says *too many redirects* | `/login` is not ignored by the filter, or the ignore path is misspelled. |
| `NullPointerException` in `LoginFilter.init()` | The name in `getInitParameter("ignore-paths")` doesn't match the `@WebInitParam` name. |
| `NullPointerException` at `userViews + 1` | The listener didn't run. Check `@WebListener`, and that the class is under `mk.ukim.finki.wp2026` (`@ServletComponentScan`). |
| Login works but the user info is not shown | The session attribute name differs: it's `"user"` in `setAttribute` and `${session.user}` in the template. |
| A page is reachable without logging in | Check the matching logic in `isIgnored`, and test that page explicitly. |

---

## 13. Try it yourself

1. **Register page:** create `/register` (servlet + `register.html`) that calls `authService.register(...)`. Show its errors, and redirect to `/login` on success. Don't forget to add `/register` to `ignore-paths`.
2. **Duplicate usernames:** make `register` throw a new `UsernameAlreadyExistsException` if the username is taken. Hint: `findByUsername`.
3. **Per-user counter:** also count views **per session** (`req.getSession()`) and show both numbers. Compare them across two browsers.
4. **Greeting:** on `hello.html`, show *"Hello, Darko"* for the logged-in user instead of using URL parameters.
