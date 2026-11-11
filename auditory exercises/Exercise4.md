# Exercise 4 – Spring MVC Controllers & REST

**Goal:** Replace servlets with Spring MVC controllers, build a full CRUD (create, read, update, delete) page for products, move login and registration to controllers, and expose the products as a REST API that returns JSON.

**You will learn:** how `@Controller` replaces a servlet, how request data is bound to method parameters, how a controller hands data to a Thymeleaf view with `Model`, the Post-Redirect-Get pattern in Spring MVC, the difference between `@Controller` and `@RestController`, and why `@RequestBody` is needed for JSON but not for HTML forms.

---

## 1. What's new

| File | Purpose |
|------|---------|
| `model/Product.java`, `model/Manufacturer.java` | the new models |
| `model/enums/ProductLevel.java` | `DISCOUNT`, `NORMAL`, `SILVER`, `GOLD` |
| `model/dto/ProductForm.java` | the data of a product coming from a request |
| `model/exceptions/*NotFoundException.java` | `ProductNotFoundException`, `CategoryNotFoundException`, `ManufacturerNotFoundException` |
| `repository/ProductRepository.java`, `ManufacturerRepository.java` + `impl/*` | data access |
| `service/ProductService.java`, `ManufacturerService.java` + `impl/*` | business rules & validation |
| `web/controller/ProductController.java` | `/products`: the HTML pages (list, add, edit, delete) |
| `web/controller/LoginController.java`, `LogoutController.java`, `RegisterController.java`, `HomeController.java` | login, logout, register and the home page |
| `web/rest/ProductRestController.java` | `/api/products`: the same products as JSON |
| `templates/products.html`, `product-form.html`, `home.html`, `register.html` | new pages. `login.html` was restyled. |

The new models follow the same layers as before: **model → repository → service → web**. Only the web layer is different: controllers instead of servlets.

> 💡 The old servlets still work, but they moved under `/servlet/...` (`/servlet/category`, `/servlet/login`, `/servlet/logout`). The plain `/login` and `/logout` now belong to the controllers.

---

## 2. Servlet vs Controller

In Exercises 1–3 a servlet had to do everything by hand: read parameters, build the `WebContext`, call the template engine, write the response. A Spring MVC controller does that plumbing for you.

| | Servlet | Controller |
|---|---------|------------|
| Class | `extends HttpServlet` | plain class with `@Controller` |
| URL | `@WebServlet(urlPatterns = ...)` | `@RequestMapping`, `@GetMapping`, `@PostMapping` |
| One class, many pages? | no: only `doGet` and `doPost` | yes: one method per URL and HTTP method |
| Reading a parameter | `req.getParameter("name")` | `@RequestParam String name` |
| Path part of the URL | parse it yourself | `@PathVariable Long id` |
| Passing data to the page | `context.setVariable(...)` | `model.addAttribute(...)` |
| Showing a page | `templateEngine.process("x.html", ...)` | `return "x";` (the **view name**) |
| Redirect | `resp.sendRedirect("/x")` | `return "redirect:/x";` |

```
Browser ──▶ LoginFilter ──▶ DispatcherServlet ──▶ ProductController ──▶ ProductService
                                   ▲                      │
                                   └──── view name ◀──────┘     then Thymeleaf renders products.html
```

The `DispatcherServlet` is the one servlet Spring Boot registers for you. It looks at the URL and the HTTP method, finds the controller method that matches, calls it, and renders the view whose name the method returned.

---

## 3. Product & Manufacturer

```java
@Data
@AllArgsConstructor
public class Product {
    private Long id;
    private String name;
    private Double price;
    private Integer quantity;
    private ProductLevel level;
    private Category category;
    private Manufacturer manufacturer;

    public Product(String name, Double price, Integer quantity, ProductLevel level,
                   Category category, Manufacturer manufacturer) {
        this.id = (long) (Math.random() * 1000);   // same trick as Category
        // ...
    }
}
```

- A product **has a** `Category` and a `Manufacturer` (objects, not ids). The form sends only the **ids**, and the service looks the objects up.
- `ProductLevel` is an **enum**: a fixed list of allowed values. It can't hold a value like `"PLATINUM"`.

`DataHolder` now also fills `manufacturers` (Mango, Nike, Amazon) and two products (*Sneakers 1* and *Sneakers 2*).

> ⚠️ `Math.random() * 1000` can give the **same id twice**, and then one product replaces the other in `save`. That's acceptable for a small demo. Real applications let the database generate ids.

---

## 4. Repository & service

`ProductRepository` has the same shape as the category repository, plus `search`:

```java
public interface ProductRepository {
    Product save(Product product);
    List<Product> findAll();
    Optional<Product> findById(Long id);
    List<Product> search(String text);
    void delete(Long id);
}
```

`save` removes the old product with the same id before adding the new one, so one method handles both **create** and **update**.

`ProductServiceImpl` holds the rules. It needs the category and the manufacturer too, so it gets **three** dependencies through the constructor:

```java
@Override
public Product create(String name, Double price, Integer quantity, ProductLevel level,
                      Long categoryId, Long manufacturerId) {
    if (name == null || name.isBlank() || price == null || quantity == null
            || level == null || categoryId == null || manufacturerId == null) {
        throw new InvalidArgumentsException();
    }
    if (price <= 0) {
        throw new InvalidArgumentsException("Price must be greater than zero.");
    }
    if (quantity < 0) {
        throw new InvalidArgumentsException("Quantity cannot be negative.");
    }

    Category category = categoryService.findById(categoryId);              // throws if it doesn't exist
    Manufacturer manufacturer = manufacturerService.findById(manufacturerId);

    return productRepository.save(new Product(name, price, quantity, level, category, manufacturer));
}
```

| Exception | Thrown when | Message |
|-----------|-------------|---------|
| `InvalidArgumentsException` | a field is missing, `price <= 0`, `quantity < 0` | *Please fill in all required fields.* or a specific message |
| `ProductNotFoundException` | no product with that id | *Product with id %d does not exist.* |
| `CategoryNotFoundException` / `ManufacturerNotFoundException` | no category / manufacturer with that id | *... does not exist.* |

`InvalidArgumentsException` got a second constructor that takes a message, so one exception can carry different texts.

The *NotFound* exceptions are annotated with `@ResponseStatus(HttpStatus.NOT_FOUND)`. If one is thrown and **nobody catches it**, Spring turns it into an HTTP **404** instead of a 500 error.

---

## 5. ProductController – the CRUD pages

```java
@Controller
@RequestMapping("/products")
public class ProductController {
    private final ProductService productService;
    private final CategoryService categoryService;
    private final ManufacturerService manufacturerService;

    // constructor injection of all three
}
```

`@RequestMapping("/products")` on the class is a **prefix** for every method in it.

| Method | URL | What it does | Returns |
|--------|-----|--------------|---------|
| `GET`  | `/products` | list products, optionally filtered by `?search=` | `products` (the page) |
| `GET`  | `/products/add-form` | empty form | `product-form` |
| `GET`  | `/products/edit-form/{id}` | form filled with the product | `product-form` |
| `POST` | `/products` | create a product | `redirect:/products` |
| `POST` | `/products/{id}` | update a product | `redirect:/products` |
| `POST` | `/products/delete/{id}` | delete a product | `redirect:/products` |

> 💡 **Why is delete a POST?** An HTML `<form>` can only send **GET** or **POST**. A GET request must never change data, because a browser or a search engine may repeat it. So the delete button is a small form with `method="post"`. The REST API in section 9 can use the real `DELETE`.

### Listing and searching
```java
@GetMapping
public String getProductPage(@RequestParam(required = false) String search, Model model) {
    List<Product> products;
    if (search != null && !search.isBlank()) {
        products = productService.searchProducts(search);
    } else {
        products = productService.listProducts();
    }

    model.addAttribute("products", products);
    model.addAttribute("search", search);
    return "products";
}
```
- `required = false` means: `?search=` may be missing, and then `search` is `null`. Without it, a missing parameter gives a **400** error.
- `model.addAttribute("products", products)` makes `${products}` available in the template.
- Returning `"products"` means: render `templates/products.html`.

> 💡 **Two different "models".** The `Model` here is `org.springframework.ui.Model`: the **bag of values for the page**. It has nothing to do with our `model` package, which holds the **classes** that describe our data (`Product`, `Category`).

### Showing the form for add and edit
```java
@GetMapping("/edit-form/{id}")
public String getEditProductPage(@PathVariable Long id, Model model) {
    model.addAttribute("product", productService.findById(id));
    model.addAttribute("levels", ProductLevel.values());
    model.addAttribute("categories", categoryService.listCategories());
    model.addAttribute("manufacturers", manufacturerService.listManufacturers());
    return "product-form";
}
```
- `{id}` in the URL goes into `@PathVariable Long id`. Spring converts the text `"42"` to a `Long` for you.
- Add and edit use **one** template. The only difference is that the edit version also gets a `product`.
- `ProductLevel.values()` returns all enum constants, for the dropdown.

---

## 6. Binding the form: `ProductForm`

A form has six fields. Writing each one as `@RequestParam` makes the method signature long, and `create` and `update` repeat it. So the fields are collected in one record:

```java
public record ProductForm(String name, Double price, Integer quantity, ProductLevel level,
                          Long categoryId, Long manufacturerId) {
}
```

```java
@PostMapping
public String createProduct(ProductForm form, Model model) {
    try {
        productService.create(form.name(), form.price(), form.quantity(), form.level(),
                form.categoryId(), form.manufacturerId());
    } catch (InvalidArgumentsException e) {
        model.addAttribute("error", e.getMessage());
        model.addAttribute("levels", ProductLevel.values());
        model.addAttribute("categories", categoryService.listCategories());
        model.addAttribute("manufacturers", manufacturerService.listManufacturers());
        return "product-form";                // show the form again, with the error
    }

    return "redirect:/products";              // Post-Redirect-Get
}
```

- A plain object parameter with **no annotation** is filled from the request parameters **by name**. So the input names in `product-form.html` must match the record's fields: `name="categoryId"`, `name="manufacturerId"`, and so on.
- Text such as `"89.99"` is converted to a `Double`, and `"GOLD"` to `ProductLevel.GOLD`. A value that can't be converted (like `abc` for the price) gives a **400** error.
- `ProductForm` lives in `model/dto` ("**D**ata **T**ransfer **O**bject"): a simple class that only carries data between the web layer and the service. It isn't stored anywhere.
- On error we **re-render** the form (as in `LoginServlet` in Exercise 3). The dropdown lists have to be added to the `Model` again, because the model of the failed request is the only data the page gets.
- On success we **redirect**, so pressing F5 can't submit the form twice (Post-Redirect-Get, see Exercise 1).

### @RequestParam or an object?

| | `@RequestParam String name, ...` | `ProductForm form` |
|---|-----|-----|
| Method signature | one parameter per field | one parameter |
| A missing field | **400** error before your code runs | arrives as `null`, and the service reports it |
| Best for | one or two parameters (`search`, `username`) | many fields that belong together |

`LoginController` and `RegisterController` still use `@RequestParam`, because their fields are few.

---

## 7. products.html and product-form.html

New things in the templates, on top of the Thymeleaf cheat sheet from Exercise 1:

| Syntax | Meaning |
|--------|---------|
| `th:each="product : ${products}"` | repeat the element for every product |
| `th:if="${products.isEmpty()}"` / `th:unless="..."` | show the element only if the condition is true / false |
| `th:href="@{/products/edit-form/{id}(id=${product.id})}"` | build a URL, putting `product.id` in place of `{id}` |
| `${product.manufacturer?.name ?: 'No manufacturer'}` | `?.` stops at `null` instead of failing, and `?:` gives a default |
| `${product} ? 'Edit product' : 'Add new product'` | choose a text with a condition |
| `th:selected="${product?.level == level}"` | pre-select the current value in a dropdown |
| `th:value="${product?.name}"` | pre-fill an input (empty on the add form, where `product` is `null`) |
| `${#numbers.formatDecimal(product.price, 1, 2)}` | show a number with 2 decimals |

The form picks its destination depending on whether a product exists:
```html
<form method="post" th:action="${product} ? @{/products/{id}(id=${product.id})} : @{/products}">
```
So the same form posts to `POST /products/{id}` when editing and to `POST /products` when adding.

The dropdowns send the **id** of the chosen option:
```html
<select class="form-select" id="category" name="categoryId">
    <option th:each="category : ${categories}"
            th:value="${category.id}"
            th:text="${category.name}"
            th:selected="${product?.category?.id == category.id}"></option>
</select>
```
The user sees the **name**, and the server receives the **id**.

---

## 8. Login, register and home as controllers

The same logic as in Exercise 3, with less code.

```java
@Controller
@RequestMapping("/login")
public class LoginController {
    @GetMapping
    public String getLoginPage() {
        return "login";
    }

    @PostMapping
    public String login(@RequestParam String username, @RequestParam String password,
                        HttpServletRequest request, Model model) {
        User user;
        try {
            user = authService.login(username, password);
        } catch (InvalidArgumentsException | InvalidUserCredentialsException e) {
            model.addAttribute("error", e.getMessage());
            return "login";
        }

        request.getSession().setAttribute("user", user);   // still the same session as in Exercise 3
        return "redirect:/products";
    }
}
```

| Controller | URL | Notes |
|------------|-----|-------|
| `HomeController` | `/` and `/home` | `@GetMapping({"/", "/home"})`: one method, two URLs |
| `LoginController` | `/login` | puts `user` in the session |
| `LogoutController` | `/logout` | `session.invalidate()`, then `redirect:/login` |
| `RegisterController` | `/register` | calls `authService.register(...)`, then `redirect:/login?registered` |

- The session works exactly as before. Templates still read `${session.user}`.
- `login.html` shows the *"Registration successful"* message with `th:if="${param.registered}"`. `param` gives access to the URL's query parameters.
- `HttpServletRequest` can simply be added as a method parameter, and Spring passes it in.

### LoginFilter changes
- The `ignore-paths` list grew, because more pages are public now: `/, /home, /login, /register, /servlet/login, /api`.
- It now uses `req.getSession(false)`. `getSession()` **creates** a session when there isn't one. `getSession(false)` returns `null` instead, so every anonymous visitor doesn't get a session just by opening the home page.

> ⚠️ `/api` is in the ignore list, so the REST API from the next section is **open to everyone**, with no login. That's fine for learning, but a real API must be protected (Spring Security, later in the course).

---

## 9. ProductRestController – JSON instead of pages

A `@Controller` returns the **name of a view**. A `@RestController` returns **data**, and Spring converts it to **JSON** (with the Jackson library). There is no template.

```java
@RestController
@RequestMapping("/api/products")
public class ProductRestController {
    private final ProductService productService;

    @GetMapping
    public List<Product> list() {
        return productService.listProducts();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    public Product create(@RequestBody ProductForm body) {
        return productService.create(body.name(), body.price(), body.quantity(), body.level(),
                body.categoryId(), body.manufacturerId());
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody ProductForm body) {
        return productService.update(id, body.name(), body.price(), body.quantity(), body.level(),
                body.categoryId(), body.manufacturerId());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }
}
```

`@RestController` is `@Controller` + `@ResponseBody`: every return value is written to the response body as JSON.

| HTTP method | URL | Meaning | Body |
|-------------|-----|---------|------|
| `GET` | `/api/products` | all products | – |
| `GET` | `/api/products/{id}` | one product | – |
| `POST` | `/api/products` | create | JSON |
| `PUT` | `/api/products/{id}` | update | JSON |
| `DELETE` | `/api/products/{id}` | delete | – |

The **same HTTP method + URL meaning** (`GET` = read, `POST` = create, `PUT` = update, `DELETE` = delete) is the core idea of a **REST** API. The URL names the **thing** (`/api/products/5`), and the HTTP method says what to do with it.

### Why `@RequestBody` here but not in `ProductController`?

The data arrives in a different format:

| | HTML form (`ProductController`) | REST client (`ProductRestController`) |
|---|---|---|
| `Content-Type` | `application/x-www-form-urlencoded` | `application/json` |
| Looks like | `name=Shoes&price=89.99&categoryId=1` | `{"name": "Shoes", "price": 89.99, "categoryId": 1}` |
| Data lives in | request **parameters** | request **body** |
| Annotation | none (binding by name) | `@RequestBody` |

Without `@RequestBody`, the JSON would be ignored and every field would be `null`. With `@RequestBody` on the HTML controller, the form post would fail with **415 Unsupported Media Type**. The annotation, not the class, decides where the data comes from. That's why the **same** `ProductForm` works for both.

> 💡 Responses are converted too. Returning a `Product` gives `{"id":42,"name":"Sneakers 1","price":4300.0,"quantity":5,"level":"NORMAL","category":{...},"manufacturer":{...}}`. The nested `Category` and `Manufacturer` objects become nested JSON objects.

---

## 10. Running & testing

**The pages**
1. Open http://localhost:9090/. You see the home page, and it works without logging in.
2. Open http://localhost:9090/products. You are redirected to `/login`.
3. Log in with a wrong password. You see *Invalid username or password.*
4. Log in as `darko.sasanski` / `ds`. You land on the products page.
5. Search for `Sneakers`. Only matching products are shown. (The search is case-sensitive.)
6. Click **Add new product** and submit it with the price `-5`. You see *Price must be greater than zero.* and the form is shown again.
7. Add a valid product. You are redirected to the list and see the new row.
8. **Edit** it, then **Delete** it.
9. Try http://localhost:9090/register to create a user, then log in as that user.
10. Click **Logout**, then open `/products` again. You are redirected to `/login`.

**The API** (pick a real id from the first request, since ids are random)
```bash
curl http://localhost:9090/api/products

curl http://localhost:9090/api/products/<id>

curl -X POST http://localhost:9090/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Running shoes","price":89.99,"quantity":10,"level":"GOLD","categoryId":<categoryId>,"manufacturerId":<manufacturerId>}'

curl -X DELETE http://localhost:9090/api/products/<id>

curl -i http://localhost:9090/api/products/-1      # 404 Not Found
```
The category and manufacturer ids can be read from the `category.id` and `manufacturer.id` fields of the first request.

---

## 11. Key concepts

1. **A controller replaces a servlet.** The URL and HTTP method choose the method, and Spring does the plumbing.
2. **A returned `String` is a view name.** `"products"` renders `products.html`, and `"redirect:/products"` sends a redirect.
3. **`Model` carries data to the page.** `@RequestParam` and `@PathVariable` bring data in.
4. **An object parameter** is filled from the request parameters by name. It's the clean way to receive many fields.
5. **Post-Redirect-Get:** after a successful POST, redirect. After a failed one, show the form again with the error.
6. **`@RestController` returns JSON, not views.** `@RequestBody` reads a JSON body, and form posts don't need it.
7. **In REST, the URL is the thing and the HTTP method is the action.**
8. **`@ResponseStatus` on an exception** turns it into a specific HTTP status, like 404.

---

## 12. Common problems

| Symptom | Fix |
|---------|-----|
| *Whitelabel Error Page*, status 404 | The URL or the HTTP method doesn't match any mapping. A form with `method="post"` needs a `@PostMapping`. |
| Template error: *Error resolving template "xyz"* | The returned view name doesn't match a file in `templates/` (without `.html`). |
| `400 Bad Request` on a form | A `@RequestParam` is missing, or a value can't be converted. Check the input `name` attributes. |
| The form saves, but a field is empty | The input `name` doesn't match the `ProductForm` field. It is `categoryId`, not `category`. |
| `415 Unsupported Media Type` on the API | The `Content-Type: application/json` header is missing. |
| API fields are all `null` | `@RequestBody` is missing, or the JSON keys don't match the field names. |
| The API gives a 500 error for an incomplete body | `InvalidArgumentsException` has no `@ResponseStatus`. See **Try it yourself**. |
| After a failed submit the dropdowns are empty | The lists (`levels`, `categories`, `manufacturers`) weren't added to the `Model` again before returning the form. |
| `/api/...` redirects to `/login` | `/api` isn't in the `ignore-paths` of `LoginFilter`. |
| `PropertyNotFoundException` / null in the template | An attribute name in the `Model` differs from the one used in the template. |

---

## 13. Try it yourself

1. **API errors:** annotate `InvalidArgumentsException` with `@ResponseStatus(HttpStatus.BAD_REQUEST)` and check that an incomplete JSON body now gives a **400**. Is that a good idea for the HTML controller too? Why does it still work there?
2. **Search in the API:** add an optional `?search=` parameter to `GET /api/products`, like in `ProductController`.
3. **Manufacturers:** build the pages for manufacturers (`ManufacturerController`, `manufacturers.html`, a form). The service and repository are ready.
4. **Status codes:** make `POST /api/products` return **201 Created** and `DELETE` return **204 No Content**. Hint: `ResponseEntity`, or `@ResponseStatus` on the method.
5. **Case-insensitive search:** change `InMemoryProductRepositoryImpl.search` so *"sneakers"* also finds *"Sneakers 1"*.
6. **A page for categories:** show the category's products when its name is clicked. Which new method does the repository need?
