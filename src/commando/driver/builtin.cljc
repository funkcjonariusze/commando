(ns commando.driver.builtin
  "Built-in drivers for Commando post-processing.
   Extend `commando.impl.executing/command-driver` multimethod.

   Vector DSL:
     :=> :keyword              — driver without params
     :=> [:driver-name params] — driver with params

   Pipeline (first element is a vector):
     :=> [[:get :address] [:get-in [:location :city]] :uppercase]

   Examples:
     :=> [:get :name]
     :=> [:get-in [:address :city]]
     :=> [:select-keys [:name :email]]
     :=> [:projection [[:user-id :id] [:city [:address :city]]]]
     :=> [:fn inc]
     :=> :uppercase
     :=> [[:get-in [:profile :name]] :uppercase]"
  (:require
   [commando.impl.executing :as executing]
   [commando.impl.utils :as commando-utils]))

;; -- identity --

(defmethod executing/command-driver :identity
  [_ _driver-params applied-result _command-data _instruction _command-path-obj]
  ;; => [:identity] pass-through
  applied-result)

;; -- :get-in --

(defmethod executing/command-driver :get-in
  [_ driver-params applied-result _command-data _instruction _command-path-obj]
  ;; :=> [:get-in [:address :city]]
  ;; No params = identity pass-through (default behavior).
  (let [path (first driver-params)]
    (if (seq path)
      (get-in applied-result path)
      applied-result)))

;; -- :get --

(defmethod executing/command-driver :get
  [_ driver-params applied-result _command-data _instruction _command-path-obj]
  ;; :=> [:get :name]
  ;; No params = identity pass-through (default behavior).
  (if-let [k (first driver-params)]
    (get applied-result k)
    applied-result))

;; -- :select-keys --

(defmethod executing/command-driver :select-keys
  [_ driver-params applied-result _command-data _instruction _command-path-obj]
  ;; :=> [:select-keys [:name :email]]
  (let [ks (first driver-params)]
    (select-keys applied-result ks)))

;; -- :fn --

(defmethod executing/command-driver :fn
  [_ driver-params applied-result _command-data _instruction _command-path-obj]
  ;; :=> [:fn inc]
  ;; :=> [:fn :keyword]
  (let [f (commando-utils/resolve-fn (first driver-params))]
    (if f (f applied-result) applied-result)))

;; -- :projection --

(defmethod executing/command-driver :projection
  [_ driver-params applied-result _command-data _instruction _command-path-obj]
  ;; :=> [:projection [[:user-id :id]
  ;;                   [:user-name [:profile :full-name]]
  ;;                   [:city [:address :location :city]]]]
  ;;
  ;; Field spec:
  ;;   [output-key]                — (get result output-key)
  ;;   [output-key source]         — source: keyword, string, or vector path
  (let [fields (first driver-params)]
    (if (empty? fields)
      applied-result
      (into {}
        (map
          (fn [[output-key source]]
            (let [value (if (nil? source)
                          (get applied-result output-key)
                          (if (vector? source)
                            (get-in applied-result source)
                            (get applied-result source)))]
              [output-key value]))
          fields)))))

;; -- :pipe (pipeline) --

(defn- resolve-pipe-step
  "Parses a single pipeline step into [driver-name driver-params].
   Step forms:
     :keyword           -> [:keyword nil]
     \"string\"         -> [:keyword nil]
     [:get :name]       -> [:get (:name)]
     [\"get\" \"name\"] -> [:get (\"name\")]"
  [step]
  (cond
    (keyword? step) [step nil]
    (string? step)  [(keyword step) nil]
    (vector? step)  (let [drv (first step)]
                      [(if (string? drv) (keyword drv) drv)
                       (rest step)])))

(defmethod executing/command-driver :pipe
  [_ driver-params applied-result command-data instruction command-path-obj]
  ;; :=> [[:get :address] [:get-in [:location :city]] :uppercase]
  ;; driver-params IS the raw vector of steps (resolve-command-driver passes it as-is)
  (reduce
    (fn [result step]
      (let [[drv-name drv-params] (resolve-pipe-step step)]
        (executing/command-driver
          drv-name drv-params result
          command-data instruction command-path-obj)))
    applied-result
    driver-params))
