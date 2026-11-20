# Exercise 6 – Database, Spring Data JPA & Profiles

**Goal:** Replace the in-memory lists with a real database: H2 while developing, PostgreSQL in Docker for "production". Turn the models into JPA entities, let Spring Data write the repositories, and use profiles to switch between the two setups.

**You will learn:** what a profile is and how `application-{profile}.properties` works, how `@Profile` decides whether a bean exists, how to run PostgreSQL with Docker Compose and look into H2 with its web console, the JPA annotations for entities and relations, how Spring Data JPA builds a repository from an interface and a method name, what `ddl-auto` does, and how lazy loading works with `open-in-view`.

---

## 1. What's new

| File | Purpose |
|------|---------|
| `pom.xml` | `spring-boot-starter-data-jpa`, `spring-boot-h2console`, the `h2` and `postgresql` drivers |
| `application.properties` | `spring.profiles.active=dev` |
| `application-dev.properties` | H2 in memory, the H2 console, SQL in the log |
| `application-prod.properties` | PostgreSQL |
| `docker-compose.yml` | a PostgreSQL 18 container |
| `model/*` | now JPA **entities** (`@Entity`) |
| `repository/*` | now interfaces that extend `JpaRepository`. Spring Data writes the code. |
| `repository/impl/*` | **deleted**: the in-memory implementations |
| `bootstrap/DataHolder.java` | no more static lists: it only seeds the test data, and only in the `dev` profile (`@Profile("dev")`) |
| `web/filter/LoginFilter.java` | `/h2` added to `ignore-paths` |

> 💡 The in-memory version is still in the history: check out the end of Exercise 5 to compare.

Exercise 2 promised: *"when we replace the in-memory list with a real database later in the course, only the repository layer changes"*. Here it is: the services changed in **8 lines** (only the repository calls), and the controllers and templates didn't change at all.

---

## 2. Two databases, two profiles

| | `dev` (default) | `prod` |
|---|---|---|
| Database | H2, in memory, inside the app | PostgreSQL, in a Docker container |
| Tables | created at start, dropped at stop (`create-drop`) | created once, then kept (`update`) |
| Data after restart | gone | kept |
| Test data | yes (`DataHolder`) | no, the database starts empty |
| Extras | H2 console at `/h2`, SQL in the log | – |

A **profile** is a named set of configuration. Spring always reads `application.properties`, and then **also** `application-{profile}.properties` for the active profile, which overrides it.

**`application.properties`**
```properties
spring.application.name=wp-2026

server.port=9090

spring.profiles.active=dev
```

**`application-dev.properties`**
```properties
spring.datasource.url=jdbc:h2:mem:wp2026
spring.datasource.username=sa
spring.datasource.password=

spring.h2.console.enabled=true
spring.h2.console.path=/h2

spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
```

**`application-prod.properties`**
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/wp2026
spring.datasource.username=wp
spring.datasource.password=${DB_PASSWORD:wp}

spring.jpa.hibernate.ddl-auto=update
```
- `${DB_PASSWORD:wp}` means: use the environment variable `DB_PASSWORD`, or `wp` if it isn't set. Real passwords don't belong in Git.
- The SQL dialect isn't set: Hibernate detects it from the database.

**Switching to `prod`** – any one of these:
- change `spring.profiles.active=prod` in `application.properties`,
- IntelliJ: *Run → Edit Configurations → Active profiles:* `prod`,
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=prod`,
- the environment variable `SPRING_PROFILES_ACTIVE=prod`.

### PostgreSQL in Docker
```yaml
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: wp2026
      POSTGRES_USER: wp
      POSTGRES_PASSWORD: wp
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql

volumes:
  pgdata:
```
> 💡 From PostgreSQL 18 the official image keeps its data in a version folder under `/var/lib/postgresql`, so the volume is mounted there. Older tutorials (PostgreSQL 17 and earlier) mount `/var/lib/postgresql/data`.

```bash
docker compose up -d      # start the database in the background
docker compose down       # stop it, the data stays in the pgdata volume
docker compose down -v    # stop it and delete the data
```

### H2 console
In `dev`, open http://localhost:9090/h2 and connect with:

| Field | Value |
|-------|-------|
| JDBC URL | `jdbc:h2:mem:wp2026` (the default `jdbc:h2:~/test` is a different database) |
| User Name | `sa` |
| Password | *(empty)* |

> ⚠️ Since Spring Boot 4 the console is a separate module, `spring-boot-h2console`. Without it `/h2` simply doesn't exist. The path also had to be added to `ignore-paths` in `LoginFilter`, or it would redirect to `/login`.

---

## 3. `@Profile` – a bean only for some profiles

The properties files configure **values** per profile. `@Profile` does the same for **beans**: the class becomes a bean only when the profile is active.

```java
@Component
@Profile("dev")
@AllArgsConstructor
public class DataHolder {
    private final CategoryRepository categoryRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @PostConstruct
    public void init() {
        if (this.categoryRepository.count() == 0) {
            this.categoryRepository.save(new Category("Movies", "Movies Category"));
            // ... Books, Clothes
        }

        if (this.manufacturerRepository.count() == 0) {
            // ... Mango, Nike, Amazon
        }

        if (this.productRepository.count() == 0) {
            Category clothes = this.categoryRepository.findByName("Clothes").orElseThrow();
            Manufacturer nike = this.manufacturerRepository.findByName("Nike").orElseThrow();
            // ... Sneakers 1, 2 and 3
        }

        if (this.userRepository.count() == 0) {
            // ... the three users
        }
    }
}
```

- In `dev` the test data is there on every start. In `prod` `DataHolder` **doesn't exist at all**, so the database starts empty: register the first user on `/register`, then add categories, manufacturers and products on their pages. A real shop never starts with fake products and known passwords.
- `@Profile("!prod")` means "every profile except `prod`", and `@Profile({"dev", "test"})` means "either of the two".
- `@PostConstruct` runs once, right after Spring has created the bean and injected the four repositories, so `init()` can use them.
- Each table is filled **only if it's empty** (`count() == 0`), so a database that keeps its data doesn't get the same test data again on every start. Our `dev` database is empty at every start anyway (`create-drop`), but the checks matter as soon as `dev` keeps its data, e.g. with a file database (`jdbc:h2:file:./data/wp2026`) and `ddl-auto=update`.
- The products need the *Clothes* category and the *Nike* manufacturer. They're looked up with `findByName` (a query method, see §5), so this also works when categories and manufacturers were already in the database.

`DataHolder` keeps its name, but its static lists are gone: they only existed for the in-memory repositories. The data now lives in the database, and `DataHolder` only fills it.

| Annotation | Question it answers | Exercise |
|------------|---------------------|----------|
| `@Primary` / `@Qualifier` | Two beans exist. **Which one** is injected? | 5 |
| `@Profile` | **Does** this bean exist in the current profile? | 6 |

---

## 4. Entities

An **entity** is a class whose objects are stored as rows of a table. JPA (the Jakarta Persistence API) defines the annotations, and Hibernate does the work behind them.

```java
@Data
@NoArgsConstructor
@Entity
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Double price;

    private Integer quantity;

    @Enumerated(EnumType.STRING)
    private ProductLevel level;

    @ManyToOne
    private Category category;

    @ManyToOne
    private Manufacturer manufacturer;

    public Product(String name, Double price, Integer quantity, ProductLevel level, Category category, Manufacturer manufacturer) {
        // ... no id: the database generates it
    }
}
```

| Annotation | Meaning |
|------------|---------|
| `@Entity` | this class is a table |
| `@Table(name = "users")` | the table name. `User` needs it, because `user` is a reserved word in PostgreSQL and H2. |
| `@Id` | the primary key. For `User` it is the `username`. |
| `@GeneratedValue(strategy = GenerationType.IDENTITY)` | the database generates the id (1, 2, 3 …). `Math.random()` is finally gone. |
| `@Enumerated(EnumType.STRING)` | stores `"GOLD"`, not the position `3` |
| `@ManyToOne` | many products → one category: a `category_id` column |
| `@ManyToMany` | a cart has many products, a product is in many carts: a separate join table |

The tables Hibernate creates (camelCase becomes snake_case):

```
category               (id, name, description)
manufacturer           (id, name, address)
product                (id, name, price, quantity, level, category_id, manufacturer_id)
users                  (username, password, name, surname)
shopping_cart          (id, date_created, status, user_username)
shopping_cart_products (shopping_cart_id, products_id)
```

> ⚠️ Without `@Enumerated(EnumType.STRING)` an enum is stored as its **position**. If someone later reorders `DISCOUNT, NORMAL, SILVER, GOLD`, every saved product silently gets a different level.

### Lombok on entities: `@Data` + `@NoArgsConstructor`
The models keep `@Data`, and get `@NoArgsConstructor` instead of `@AllArgsConstructor`:
- JPA needs a constructor **without arguments** to create objects from rows. Without it Hibernate fails with *No default constructor for entity*.
- `@AllArgsConstructor` would take the `id` too, which the database now generates. The entities keep their own constructors without `id`. `User` has its own constructor with the four fields, because its id is the `username`, which we set ourselves.

`@Data` also generates `equals`, `hashCode` and `toString` over **all** fields, related objects included. That's fine for our code, as long as we keep three rules:
1. **No relations in both directions.** Otherwise `toString` and `hashCode` call each other forever (`StackOverflowError`). A relation added on the other side needs `@ToString.Exclude` and `@EqualsAndHashCode.Exclude`.
2. **Compare by id, not by object.** The templates already do (`product?.category?.id == category.id`), and so do the services (`p.getId().equals(productId)`).
3. **Don't print or log whole entities.** `toString` on a `ShoppingCart` loads its lazy `products`, and `toString` on a `User` prints the password.

> 💡 IntelliJ may warn that `@Data` isn't recommended on JPA entities. The three rules above are the reason.

> 💡 The relation between users and carts is mapped **only** on `ShoppingCart` (`@ManyToOne User user`). A `List<ShoppingCart> carts` in `User` isn't needed by any page, and with `FetchType.EAGER` it would load all of a user's carts at every login.

---

## 5. Repositories – Spring Data JPA

Until now every repository had an interface **and** an in-memory class with streams over `DataHolder`. Now there's only the interface:

```java
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findAllByNameContaining(String name);
}
```

`JpaRepository<Product, Long>` (entity type, id type) already contains `findAll`, `findById`, `save`, `deleteById`, `count`, `existsById` and more. **Spring Data creates the implementation at startup.** That's why the services only needed new method names:

| Before (in-memory) | Now (Spring Data) |
|--------------------|-------------------|
| `delete(id)` | `deleteById(id)` |
| `search(text)` | a method named after the query, see below |
| `userRepository.findByUsername(username)` | `userRepository.findById(username)`, since `username` is the `@Id` |
| `findByUsernameAndStatus(username, status)` | `findByUserUsernameAndStatus(username, status)` |

### Queries from method names
Spring Data reads the **method name** and writes the query:

| Method | Query |
|--------|-------|
| `findByName(name)` | `where name = ?`, one result as an `Optional` |
| `findAllByNameContaining(name)` | `where name like '%name%'` |
| `findAllByNameContainingOrDescriptionContaining(name, description)` | `where name like '%…%' or description like '%…%'` |
| `findByUsernameAndPassword(username, password)` | `where username = ? and password = ?` |
| `findByUserUsernameAndStatus(username, status)` | joins `users` and filters on `user.username` and `status` |

- `findByUserUsername…` follows a **path**: `user` (the property in `ShoppingCart`), then `username` (the property in `User`).
- `Containing` adds `%…%`. `Like` doesn't: `findByNameLike("Cloth")` means *equal to "Cloth"*, which was a bug in last year's code.
- `IgnoreCase` makes a search case-insensitive: `findAllByNameContainingIgnoreCase`. The search here stays case-sensitive, as in Exercises 4 and 5.
- The names are checked **at startup**. A typo like `findAllByNamContaining` stops the app with *No property 'nam' found for type 'Product'*.
- `@Repository` isn't needed on these interfaces (Spring Data finds them anyway), but it shows which layer they belong to.

---

## 6. `ddl-auto` and the SQL log

`spring.jpa.hibernate.ddl-auto` says what Hibernate does with the tables at startup:

| Value | What it does | Used in |
|-------|--------------|---------|
| `create-drop` | creates the tables at start, drops them at stop | `dev` |
| `update` | creates missing tables and columns, **never deletes** anything | `prod` |
| `validate` | only checks that the tables match the entities | – |
| `none` | does nothing | real production, with migration tools (Flyway, Liquibase) |

With `format_sql` and `logging.level.org.hibernate.SQL=DEBUG`, every SQL statement is printed in the console in `dev`. Watch it while you click through the pages: each page is one or more `select`s.

---

## 7. Lazy loading and `open-in-view`

`@ManyToOne` is loaded **eagerly** by default: a product comes with its category. `@ManyToMany` is **lazy**: a cart's products are loaded only when someone first reads `cart.getProducts()`.

Our services don't use `@Transactional`, so the cart's products are read **after** `findById` has returned: in `addProductToShoppingCart`, in the `PriceCalculator` and while rendering `shopping-cart.html`. That works because **open-in-view** is on by default in Spring Boot: it keeps the database session open for the whole request, until the page is rendered. That's also why every start prints the warning *spring.jpa.open-in-view is enabled by default…*. We keep the default.

With `spring.jpa.open-in-view=false`, the cart fails with *LazyInitializationException: … could not initialize proxy - no Session*. The fix then is `@Transactional` on the service methods, and loading the data inside them.

---

## 8. Running & testing

**`dev` (H2)**
1. Start the application. The console shows the `create table` statements.
2. Open http://localhost:9090/h2, connect with `jdbc:h2:mem:wp2026` and run `SELECT * FROM PRODUCT`. You see the three products, and `LEVEL` contains text like `NORMAL`.
3. Log in as `darko.sasanski` / `ds`, add two products to the cart, then run `SELECT * FROM SHOPPING_CART_PRODUCTS`.
4. Restart. Everything is back to the test data.

**`prod` (PostgreSQL)**
5. `docker compose up -d`
6. Start the application with the `prod` profile.
7. Try to log in as `darko.sasanski`: it fails, because the database is empty. Register a user on `/register` and log in.
8. Add a category, a manufacturer and a product, then restart the application. They are still there.
9. `docker compose down`, then `up -d` again: the data survives. `down -v` deletes it.

---

## 9. Key concepts

1. **A profile** is a named configuration: `application-{profile}.properties` overrides `application.properties`.
2. **`@Profile`** decides whether a bean exists in the active profile.
3. **An entity** is a class mapped to a table: `@Entity`, `@Id`, `@GeneratedValue`.
4. **Relations** become foreign keys (`@ManyToOne`) or join tables (`@ManyToMany`).
5. **Spring Data JPA** implements a repository interface for you, and builds queries from method names.
6. **Layers pay off:** replacing the storage changed only the repositories and a few method calls.
7. **`ddl-auto`** controls the tables: `create-drop` for development, `update` for a database that keeps its data.
8. **Lazy relations** load on first use. `open-in-view` keeps the session open while the page renders.

---

## 10. Common problems

| Symptom | Fix |
|---------|-----|
| `prod` doesn't start: *Connection refused* | The container isn't running: `docker compose up -d`. |
| `docker compose up` fails: *port 5432 is already allocated* | Another PostgreSQL uses 5432. Map `"5433:5432"` and use port 5433 in the URL. |
| `/h2` redirects to `/login` | `/h2` is missing in `ignore-paths` of `LoginFilter`. |
| `/h2` gives 404 | `spring-boot-h2console` is missing, or `spring.h2.console.enabled` isn't `true`, or the profile isn't `dev`. |
| H2 console: *Database "…/test" not found* | The JDBC URL must be `jdbc:h2:mem:wp2026`. |
| *Syntax error … "user"* when tables are created | An entity table named `user`. Use `@Table(name = "users")`. |
| *No property '…' found for type '…'* at startup | A typo in a query method name. |
| *No default constructor for entity* | `@NoArgsConstructor` is missing. |
| Deleting a category gives a 500 error, and the log shows a foreign key violation | Products still use it, and the foreign key protects them. The in-memory version allowed it. See **Try it yourself**. |
| `LazyInitializationException` | A lazy relation was read after the session closed. Check `open-in-view`. |
| In `prod` nobody can log in | Correct: `DataHolder` is `dev`-only. Register first. |
| The level column contains numbers | `@Enumerated(EnumType.STRING)` is missing. |

---

## 11. Try it yourself

1. **Case-insensitive search:** add `IgnoreCase` to the query methods and check that *sneakers* finds *Sneakers 1*.
2. **Category in use:** before deleting, check `productRepository.existsByCategoryId(id)` and throw a new `CategoryInUseException`. Should `CategoryServiceImpl` get `ProductRepository` or `ProductService`? (Remember the circular dependency from Exercise 5.)
3. **Product in a cart:** do the same for products, with `shoppingCartRepository.existsByProductsId(id)`.
4. **Calculator per profile:** remove `@Primary` and `@Qualifier` from Exercise 5, and use `@Profile("dev")` on `RegularPriceCalculator` and `@Profile("prod")` on `LevelDiscountPriceCalculator`. Which total do you see in each profile?
5. **Money:** change `price` to `BigDecimal` with `@Column(precision = 10, scale = 2)`. What else has to change?
6. **Your own query:** write the product search with `@Query("select p from Product p where p.name like %:text%")` instead of a method name.
