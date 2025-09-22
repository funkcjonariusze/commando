# Integrating Integrant and Commando

[Integrant](https://github.com/weavejester/integrant) is a flexible library for building systems of interconnected components in Clojure. It allows you to declaratively describe the structure of a system, define dependencies between components, and manage their lifecycle.

## Why use Integrant together with Commando?

Unlike the classic approach of using only configuration maps, Commando lets you construct and transform your dependency structure as declarative instructions. This makes it easy to describe systems, reuse configuration fragments, inject extra processing logic, and define custom "commands" for specific tasks. Integrant is responsible for actual component initialization, while Commando builds, transforms, and validates configuration structures.

## Example: HTTP server + database

Imagine we're building a system with two components: an HTTP server and an SQL database connector.

```clojure
(require '[integrant.core :as ig])

(defmethod ig/init-key :plugin-jetty-server/server-main [...] ...)
(defmethod ig/init-key :plugin-sqlite/datasource [...] ...)

;; convenient component renaming
(derive :plug/db :plugin-sqlite/datasource)
(derive :plug/http-server :plugin-jetty-server/server)

(def db-storage-file "../../some-folder")

(def system:integrant-native
 {:plug/db {:dbtype "sqlite" :dbname "storage.db" :dir db-storage-file}
  :plug/http-server {:port 2500 :handler #'ring-handler :db-connector #ig/ref :plug/db}})
```

Integrant lets you build a dependency tree, e.g., the server refers to the DB connector via `#ig/ref`. However, if you need extra processing, template organization, or structure transformation, try Commando!

## System construction via Commando: a declarative approach

Commando allows you to build Integrant configurations as instructions - nested data structures where dependencies, settings, and transformations are described declaratively.

```clojure
(defn system:integrant-commando []
  {"settings" {"db-storage-file" "../../some-folder"}
   "db"
   {"sqlite-connector"
    {:integrant/component-alias :plug/db
     :integrant/component :plugin-sqlite/datasource
     :dbtype "sqlite"
     :dbname "storage.db"
     :dir {:commando/from ["settings" :project-root]}}}
   "http-server"
   {:integrant/component-alias :plug/http-server
    :integrant/component :plugin-jetty-server/server-main
    :port 2500
    :handler #'ring-handler
    :db-connector {:integrant/from ["db" "sqlite-connector"]}}
   "integrant-config"
   {:integrant/system
    [{:commando/from ["db" "sqlite-connector"]}
     {:commando/from ["http-server"]}]}})
```

Compared to the classic approach, here you can easily plug in shared settings, use aliases, build more complex dependencies, and automatically generate the final config for Integrant.

### `:integrant/component`

 This command declare what "component" exactly mean. As you see the `:apply` just only do `derive` to apply your custom naming for component

```clojure
(def command-integrant-component-spec
  {:type :integrant/component
   :recognize-fn #(and (map? %) (contains? % :integrant/component))
   :validate-params-fn (fn [m] (malli/validate
                                [:map
                                 [:integrant/component-alias :keyword]
                                 [:integrant/component :keyword]] m))
   :apply (fn [_ _ integrant-component]
               (derive
                 (get integrant-component :integrant/component-alias)
                 (get integrant-component :integrant/component))
               integrant-component)
   :dependencies {:mode :all-inside}})
```

### `:integrant/from`

This command lets you wire dependencies between components by specifying the path to the relevant config node.

```clojure
{:integrant/from ["db" "sqlite-connector"]}
```

Used to build references between components; it overlays over `#ig/ref` in Integrant.

Let's define CommandMapSpec for `:integrant/from`:

```clojure
(def command-integrant-from-spec
  {:type :integrant/from
   :recognize-fn #(and (map? %) (contains? % :integrant/from))
   :validate-params-fn (fn [m] (malli/validate [:map
                                               [:integrant/from [:sequential [:or :string :keyword :int]]]] m))
   :apply (fn [data-map _ {keyword-vector-to-component :integrant/from :as term-data}]
               (let [integrant-component (get-in data-map keyword-vector-to-component)]
                 (if (and
                       (contains? integrant-component :integrant/component)
                       (contains? integrant-component :integrant/component-alias))
                   (ig/ref (:integrant/component-alias integrant-component))
                   (throw (ex-info "`:integrant/from` Exception. term pointing on something that not a `:integrant/component` term " term-data)))))
   :dependencies {:mode :point
                     :point-key :integrant/from}})
```

Just like Commando’s basic commands, you specify how to recognize a component reference, how to validate it, and what to produce on evaluation.

### `:integrant/system`

Combines a set of components into a ready configuration map for Integrant initialization.

```clojure
{:integrant/system
 [{:commando/from ["db" "sqlite-connector"]}
  {:commando/from ["http-server"]}]}
```

And the CommandMapSpec for `:integrant/system`:

```clojure
(def command-integrant-system-spec
  {:type :integrant/system
   :recognize-fn #(and (map? %) (contains? % :integrant/system))
   :validate-params-fn (fn [m] (malli/validate
                                [:map
                                 [:integrant/system [:+ :map]]] m))
   :apply (fn [_ _ {integrant-system :integrant/system}]
               (reduce
                 (fn [acc v]
                   (assoc acc
                     (:integrant/component-alias v)
                     (dissoc v :integrant/component :integrant/component-alias)))
                 {}
                 integrant-system))
   :dependencies {:mode :all-inside}})
```

## Build and Launch the system

Once the instruction structure is built and the above command specs are placed in the registry, you can execute it via Commando to obtain the final Integrant config map:

```clojure
(def integrant-commando-registry
  (commando/build-command-registry
    [command-integrant-component
     command-integrant-from
     command-integrant-system
     commando/command-from]))

(def integrant-config-build
  (fn [commando-integrant-configuration-map]
    (let [result-status-map
          (commando/execute
            integrant-commando-registry
            commando-integrant-configuration-map)]
      (if (commando/failed? result-status-map)
        (throw (ex-info "Failed to build integrant configuration" result-status-map))
        (get-in result-status-map [:instruction "integrant-config"])))))

(def system
  (ig/init
    (integrant-config-build)
    [:plug/http-server]))
```

## Accessing environment variables via custom commands

To integrate with environment variables, you can define your own command, similarly to how it works in [juxt/aero](https://github.com/juxt/aero):

```clojure
(def command-get-env-spec
  {:type :env/get
   :recognize-fn #(and (map? %) (contains? % :env/get))
   :validate-params-fn (fn [m] (malli/validate
                                 [:map
                                  [:env/get [:string {:min 1}]]
                                  [:default {:optional true} :any]] m))
   :apply (fn [_ _ {default-value :default env-get-var :env/get}]
               (or (System/getenv env-get-var) default-value))
   :dependencies {:mode :self}})
```

Usage:
```
(commando/execute
  [command-get-env-spec]
  {:env/get "HOME"
   :default "~/"})
;; => {:instruction "/home/host-user/"}
```

## Benefits of the Commando + Integrant approach

- **Flexibility**: Easy to modify, reuse, and test configurations.
- **Declarativeness**: All dependency logic is described declaratively, making it more readable and maintainable.
- **Validation**: Commands can include their own validation and error handling logic.
- **Extensibility**: It’s easy to add your own commands for specific use cases (e.g., environment integration, value generation, integration with external systems).

This approach is especially useful when you need to frequently change or parameterize configs, automate building of complex systems, or integrate business logic directly into configuration structures.

For more advanced scenarios, you can combine Commando with other libraries, creating your own commands and registries. See the documentation in `README.md` for base command descriptions, registries, and execution interfaces.
