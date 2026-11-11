# Exercise 5 – Service Layer & Dependency Injection

**Goal:** Add a shopping cart, the most complex service so far, and use it to understand the service layer and dependency injection in depth. Then finish the CRUD pages for categories and manufacturers.

**You will learn:** what belongs in a service and what doesn't, how Spring's IoC container builds and connects beans, the three kinds of injection and why we use the constructor, why a service must not keep per-user data, what a circular dependency is, how to choose between two beans with `@Primary` and `@Qualifier`, and how flash attributes carry a message across a redirect.

---

## 1. What's new

| File | Purpose |
|------|---------|
| `model/ShoppingCart.java` | a user's cart: date, user, products, status |
| `model/enums/ShoppingCartStatus.java` | `CREATED`, `CANCELED`, `FINISHED` |
| `model/exceptions/ProductAlreadyInShoppingCartException.java` | the same product added twice (**409 Conflict**) |
| `model/exceptions/ShoppingCartNotFoundException.java`, `UserNotFoundException.java` | **404** |
| `repository/ShoppingCartRepository.java` + `impl/InMemoryShoppingCartRepositoryImpl.java` | cart data access |
| `service/ShoppingCartService.java` + `impl/ShoppingCartServiceImpl.java` | the cart rules |
| `service/PriceCalculator.java` + `impl/RegularPriceCalculator.java`, `impl/LevelDiscountPriceCalculator.java` | two ways to calculate the cart total: one interface, two beans |
| `web/controller/ShoppingCartController.java` | `/shopping-cart` |
| `web/controller/CategoryController.java`, `ManufacturerController.java` | CRUD pages for categories and manufacturers |
| `templates/shopping-cart.html`, `categories.html`, `category-form.html`, `manufacturers.html`, `manufacturer-form.html` | new pages |
| `templates/servlet/categories.html` | the old servlet page, moved here |

The header now links to **Products**, **Categories** and **Manufacturers**, and a logged-in user also sees a **Shopping cart** button. `DataHolder` seeds a third product, *Sneakers 3*, with the level `DISCOUNT`.

---

## 2. The service layer – what goes where

Since Exercise 2 every feature has gone through the same layers. With the shopping cart, the difference between them becomes clear:

| Layer | Its job | In the shopping cart |
|-------|---------|----------------------|
| **Controller** | HTTP: read the request, find the logged-in user, choose the page, redirect | `ShoppingCartController` reads the user from the session and redirects to `/shopping-cart` |
| **Service** | the business rules | one active cart per user, create it when missing, no product twice |
| **Repository** | store and find data, nothing else | save a cart, find a cart by id, by username and status |

> 💡 A good test: could the same rule be needed by a **different** web layer, such as a REST API or a mobile app? Then it belongs in the service. "A product can't be in the cart twice" is such a rule. "Redirect to `/shopping-cart`" is not.

---

## 3. ShoppingCart model

```java
@Data
public class ShoppingCart {
    private Long id;
    private LocalDateTime dateCreated;
    private User user;
    private List<Product> products;
    private ShoppingCartStatus status;

    public ShoppingCart(User user) {
        this.id = (long) (Math.random() * 1000);
        this.dateCreated = LocalDateTime.now();
        this.user = user;
        this.products = new ArrayList<>();
        this.status = ShoppingCartStatus.CREATED;
    }
}
```

- A cart **belongs to** one user and **has many** products.
- `status` says what happened to the cart: `CREATED` is the **active** one the user is filling, `FINISHED` was bought, `CANCELED` was abandoned. A user has at most one `CREATED` cart, but can have many old ones. Keeping the old carts lets a shop see past purchases.

The repository finds the active cart with:
```java
Optional<ShoppingCart> findByUsernameAndStatus(String username, ShoppingCartStatus status);
```

> ⚠️ `save` removes the old cart **with the same id** before adding the new one, as in every other repository. Last year's version removed by **username**, so saving a new cart silently deleted all of the user's finished carts.

---

## 4. ShoppingCartService – the rules

```java
@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    private final ShoppingCartRepository shoppingCartRepository;
    private final UserRepository userRepository;
    private final ProductService productService;

    public ShoppingCartServiceImpl(ShoppingCartRepository shoppingCartRepository,
                                   UserRepository userRepository,
                                   ProductService productService) {
        this.shoppingCartRepository = shoppingCartRepository;
        this.userRepository = userRepository;
        this.productService = productService;
    }

    @Override
    public ShoppingCart getActiveShoppingCart(String username) {
        return shoppingCartRepository.findByUsernameAndStatus(username, ShoppingCartStatus.CREATED)
                .orElseGet(() -> {
                    User user = userRepository.findByUsername(username)
                            .orElseThrow(() -> new UserNotFoundException(username));
                    return shoppingCartRepository.save(new ShoppingCart(user));
                });
    }

    @Override
    public ShoppingCart addProductToShoppingCart(String username, Long productId) {
        ShoppingCart shoppingCart = getActiveShoppingCart(username);
        Product product = productService.findById(productId);        // throws if it doesn't exist

        if (shoppingCart.getProducts().stream().anyMatch(p -> p.getId().equals(productId))) {
            throw new ProductAlreadyInShoppingCartException(productId, username);
        }

        shoppingCart.getProducts().add(product);
        return shoppingCartRepository.save(shoppingCart);
    }
}
```

- `orElseGet(() -> ...)` runs the lambda **only** when the `Optional` is empty. So the cart is created the first time the user needs it, not at login.
- `anyMatch(...)` returns `true` if at least one product in the list matches.
- `listAllProductsInShoppingCart(cartId)` finds the cart by id and returns its products, or throws `ShoppingCartNotFoundException`.

### Why `ProductService`, but `UserRepository`?

`ShoppingCartServiceImpl` depends on **three** beans of different kinds (a fourth one, `PriceCalculator`, is explained in §5):

| Dependency | Why |
|------------|-----|
| `ShoppingCartRepository` | its own data |
| `ProductService` | reuses `findById`, which already throws `ProductNotFoundException`. No need to write that check again. |
| `UserRepository` | there is no `UserService` yet (`AuthService` is about login and register), so it uses the repository directly |

A service may use **another service** or **another entity's repository**. What it must never do is reach **up** into the web layer (sessions, `Model`, redirects).

---

## 5. Inversion of Control & Dependency Injection

### IoC – who creates the objects?
In plain Java **you** create objects with `new` and connect them by hand. In Spring, the **IoC container** does it: at startup it finds every class marked with `@Component`, `@Repository`, `@Service` or `@Controller`, creates **one** object of each (a **bean**), and passes beans into each other's constructors. The control over object creation is **inverted**: it moves from your code to the framework.

### DI – how a bean gets what it needs
**Dependency injection** is the technique: a class only **declares** what it needs, and the container **injects** it. For the shopping cart page, Spring builds this graph at startup, from the bottom up:

```
ShoppingCartController
 └─ ShoppingCartServiceImpl
     ├─ InMemoryShoppingCartRepositoryImpl
     ├─ InMemoryUserRepositoryImpl
     ├─ ProductServiceImpl
     │   ├─ InMemoryProductRepositoryImpl
     │   ├─ CategoryServiceImpl ── InMemoryCategoryRepositoryImpl
     │   └─ ManufacturerServiceImpl ── InMemoryManufacturerRepositoryImpl
     └─ LevelDiscountPriceCalculator   (one of two PriceCalculator beans, see below)
```

Every arrow points to an **interface** (`ProductService`, `ShoppingCartRepository`). `ShoppingCartServiceImpl` never knows which class implements them. That's why, in Exercise 6, the in-memory repositories can be replaced by a database **without changing a single service**.

### Three ways to inject

```java
// 1. Constructor injection – what we use everywhere
private final ProductService productService;

public ShoppingCartServiceImpl(ProductService productService) {
    this.productService = productService;
}

// 2. Setter injection
private ProductService productService;

@Autowired
public void setProductService(ProductService productService) {
    this.productService = productService;
}

// 3. Field injection
@Autowired
private ProductService productService;
```

| | Constructor | Setter | Field |
|---|---|---|---|
| Field can be `final` | yes | no | no |
| Dependencies visible | in the constructor | scattered over setters | hidden in fields |
| Object can exist without its dependencies | no | yes | yes |
| Easy to test with `new` | yes | needs extra calls | needs reflection |
| `@Autowired` needed | no, if there is one constructor | yes | yes |

Use **constructor injection**. You'll still see `@Autowired` fields in old tutorials. They work, but hide what a class depends on.

> 💡 `DataHolder` uses Lombok's `@AllArgsConstructor`. It generates the constructor for its four `final` repository fields, so it's constructor injection too, just written for you.

### Beans are singletons
By default Spring creates **one** bean of each class, and every request from every user goes through that same object. So a service must **never** keep per-user data in a field:

```java
@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    private List<Product> products = new ArrayList<>();   // ❌ one list shared by ALL users
}
```

If Darko adds a product, Elena would see it too. That's why carts live in the repository (one cart per user), and the service only holds references to other beans, which are the same for everyone.

### Circular dependencies
If `CategoryServiceImpl` also needed `ProductService` (for example, to check whether a category is still used), Spring would have a loop: to create `ProductServiceImpl` it needs `CategoryServiceImpl`, which needs `ProductServiceImpl`… The application **doesn't start** and prints something like:

```
The dependencies of some of the beans in the application context form a cycle:
┌─────┐
|  productServiceImpl
↑     ↓
|  categoryServiceImpl
└─────┘
```

The fix is in the design: use the **repository** (`ProductRepository`) instead of the service, or move the rule to the service that already depends on both.

### Two beans for one interface: `@Primary` and `@Qualifier`
In Exercise 2 the advice for `expected single matching bean but found 2` was to keep only one bean for now. The cart total is a real case where we want **two**.

```java
public interface PriceCalculator {
    Double calculateTotal(List<Product> products);
}
```

| Bean | How it calculates | Annotation |
|------|-------------------|------------|
| `RegularPriceCalculator` | the sum of the prices | `@Service @Primary` |
| `LevelDiscountPriceCalculator` | 20% off products with level `DISCOUNT` | `@Service` |

```java
@Service
public class LevelDiscountPriceCalculator implements PriceCalculator {
    @Override
    public Double calculateTotal(List<Product> products) {
        return products.stream()
                .mapToDouble(product -> switch (product.getLevel()) {
                    case DISCOUNT -> product.getPrice() * 0.8;
                    case NORMAL, SILVER, GOLD -> product.getPrice();
                })
                .sum();
    }
}
```
The `switch` expression has no `default`. If a new `ProductLevel` is added, this code stops compiling until the new level is handled.

`ShoppingCartServiceImpl` asks for a `PriceCalculator` and uses it in `getTotalPrice(cartId)`:

```java
public ShoppingCartServiceImpl(ShoppingCartRepository shoppingCartRepository,
                               UserRepository userRepository,
                               ProductService productService,
                               @Qualifier("levelDiscountPriceCalculator") PriceCalculator priceCalculator) {
    // ...
}
```

Two beans implement `PriceCalculator`, so Spring has to be told which one to inject. Try the three steps (with all three products in the cart):

| Step | Code | Result |
|------|------|--------|
| 1 | No `@Primary`, no `@Qualifier` | The app **doesn't start**: *Parameter 3 of constructor in ShoppingCartServiceImpl required a single bean, but 2 were found*. Spring Boot suggests `@Primary` or `@Qualifier`. |
| 2 | `@Primary` on `RegularPriceCalculator` | The app starts. Total **13,100.00** (4,300 + 6,300 + 2,500) |
| 3 | `@Qualifier("levelDiscountPriceCalculator")` on the parameter | Total **12,600.00**: Sneakers 3 is 20% off |

- `@Primary` marks the **default** bean for **every** place that asks for the interface.
- `@Qualifier("...")` picks a bean **by name** at **one** injection point, and wins over `@Primary`.
- A bean's name is its class name with a lowercase first letter: `levelDiscountPriceCalculator`.
- `ShoppingCartServiceImpl` only knows the **interface**. Switching the calculation changes one annotation, not the service's logic.

---

## 6. ShoppingCartController and flash attributes

| Method | URL | What it does |
|--------|-----|--------------|
| `GET` | `/shopping-cart` | shows the products in the active cart and their total |
| `POST` | `/shopping-cart/add-product/{id}` | adds a product, then `redirect:/shopping-cart` |

```java
@PostMapping("/add-product/{id}")
public String addProductToShoppingCart(@PathVariable Long id,
                                       HttpServletRequest request,
                                       RedirectAttributes redirectAttributes) {
    User user = (User) request.getSession().getAttribute("user");

    try {
        shoppingCartService.addProductToShoppingCart(user.getUsername(), id);
    } catch (ProductAlreadyInShoppingCartException e) {
        redirectAttributes.addFlashAttribute("error", e.getMessage());
    }

    return "redirect:/shopping-cart";
}
```

The controller **redirects** after the POST (Post-Redirect-Get), so the error has to survive the redirect. A normal `Model` attribute can't: it belongs to the POST request, and the redirect starts a new GET request.

| Option | Survives a redirect? | Visible in the URL? |
|--------|----------------------|---------------------|
| `model.addAttribute("error", ...)` | no | – |
| `"redirect:/shopping-cart?error=" + message` | yes | yes, and the text must be URL-encoded |
| `redirectAttributes.addFlashAttribute("error", ...)` | yes, for **one** request | no |

A **flash attribute** is kept in the session just until the next request, then moved into that request's `Model` and deleted. So `shopping-cart.html` reads it like any other attribute:

```html
<div class="alert alert-danger" th:if="${error}" th:text="${error}"></div>
```

Press F5 and the message is gone, because it was only meant for one request.

> 💡 The "Add to cart" button on `products.html` is a small `method="post"` form, like the delete button, because it changes data.

---

## 7. CRUD for categories and manufacturers

Both controllers follow the `ProductController` pattern from Exercise 4:

| Method | Category | Manufacturer |
|--------|----------|--------------|
| `GET` list | `/categories` | `/manufacturers` |
| `GET` add form | `/categories/add-form` | `/manufacturers/add-form` |
| `GET` edit form | `/categories/edit-form/{id}` | `/manufacturers/edit-form/{id}` |
| `POST` create | `/categories` | `/manufacturers` |
| `POST` update | `/categories/{id}` | `/manufacturers/{id}` |
| `POST` delete | `/categories/delete/{id}` | `/manufacturers/delete/{id}` |

The method names follow one rule in every controller: `getCategoryPage`, `getAddCategoryPage`, `getEditCategoryPage`, `createCategory`, `updateCategory`, `deleteCategory`.

These forms have only **two** fields each, so the controllers use `@RequestParam String name, @RequestParam String description` instead of a form object (see the table in Exercise 4, §6).

> ⚠️ Two templates can't have the same name. The servlet from Exercise 2 already used `categories.html`, so its page moved to `templates/servlet/categories.html`. `CategoryServlet` and `MessyCategoryServlet` render that one now, and `/servlet/category` still works.

---

## 8. Running & testing

1. Log in as `darko.sasanski` / `ds` and open **Products**.
2. Click **Add to cart** on *Sneakers 1*. You land on the cart page, with one product.
3. Add *Sneakers 1* again. A red message says the product is already in the cart. Press F5: the message disappears.
4. Add *Sneakers 2* and *Sneakers 3*. The **Total** row shows 12,600.00, not 13,100.00, because *Sneakers 3* is a `DISCOUNT` product and the cart uses `LevelDiscountPriceCalculator`.
5. Log out and log in as `elena.atanasoska` / `ea`. Her cart is empty: carts are per user.
6. Log in as Darko again. His cart still has the products, because they're stored in `DataHolder`, not in the session.
7. Open **Categories**. Add, edit and delete a category. Try a name with only spaces: the form shows *Please fill in all required fields.*
8. Do the same on **Manufacturers**.
9. Open http://localhost:9090/servlet/category. The old servlet page still works.
10. Restart the application. All carts are gone, because everything is in memory.

---

## 9. Key concepts

1. **Service = business rules.** The controller deals with HTTP, the repository with data.
2. **IoC:** Spring, not your code, creates the objects (beans) and connects them.
3. **DI:** a class declares its dependencies, preferably in the **constructor**, and Spring passes them in.
4. **Depend on interfaces**, so an implementation can be replaced without touching the classes that use it.
5. **Beans are singletons:** never keep per-user data in a service field.
6. **No circular dependencies** between beans. Fix the design, not the error.
7. **`@Primary` / `@Qualifier`** choose between two beans of the same type.
8. **Flash attributes** carry a message across one redirect.

---

## 10. Common problems

| Symptom | Fix |
|---------|-----|
| `No qualifying bean of type '...ShoppingCartRepository'` | The implementation is missing `@Repository`, or it's outside the `mk.ukim.finki.wp2026` package. |
| `The dependencies of some of the beans ... form a cycle` | Two beans need each other. Use a repository instead of the other service. |
| `expected single matching bean but found 2` / `required a single bean, but 2 were found` | Two classes implement the same interface. Use `@Primary` or `@Qualifier`. |
| `No qualifying bean of type '...PriceCalculator'` with `@Qualifier(...)` in the message | The name in `@Qualifier` doesn't match a bean. It's the class name with a lowercase first letter. |
| The error message never shows on the cart page | It was added with `model.addAttribute` before a redirect. Use `redirectAttributes.addFlashAttribute`. |
| `NullPointerException` at `user.getUsername()` | No user in the session. `/shopping-cart` must not be in `ignore-paths`, and the attribute is called `"user"`. |
| The Category page shows the old servlet page (or the other way round) | The template names are mixed up: the servlet uses `servlet/categories.html`, the controller `categories`. |
| A user's old carts disappear | `save` removes by username instead of by id. |
| A deleted category still shows in a product | The product holds the `Category` object, not its id. With a database (Exercise 6) this becomes a foreign-key error instead. |

---

## 11. Try it yourself

1. **Remove from cart:** add `POST /shopping-cart/remove-product/{id}` and a button in `shopping-cart.html`.
2. **Price per row:** show the price after the discount on every row, not only in the total. Which bean should calculate it?
3. **Checkout:** a **Buy** button sets the cart to `FINISHED`. The next visit to `/shopping-cart` should give a new, empty cart.
4. **Category in use:** don't allow deleting a category that a product still uses. Which dependency can `CategoryServiceImpl` take without creating a cycle?
5. **See DI fail:** go through the three steps from §5 yourself. Then misspell the name in `@Qualifier` and read the error.
6. **Search categories:** `CategoryService.searchCategories` already exists. Add a search box to `categories.html`, like on the products page.
