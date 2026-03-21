(ns commando.debug
  "Debug visualization tools for commando instruction execution.

   Main entry points:
     execute-debug — execute and visualize a single instruction
     execute-trace — trace all nested execute calls with timing

   Display modes for execute-debug / pprint-debug:
     :tree         — enriched data flow tree with values (default)
     :table        — tabular execution map (order, deps, results)
     :graph        — compact dependency graph (structure only)
     :stats        — execution statistics (timing, counts, errors)
     :instr-before — wide pprint of the original instruction
     :instr-after  — wide pprint of the executed instruction"
  (:require
   [commando.core :as commando]
   [commando.impl.command-map :as cm]
   [commando.impl.utils :as utils]
   [clojure.string :as str]
   #?(:clj [clojure.pprint :as pp])))

;; ============================================================
;; Frame helpers
;; ============================================================

(def ^:private frame-width 60)

(defn ^:private frame-top
  [title]
  (let [prefix (str "── " title " ")
        fill   (max 0 (- frame-width (count prefix)))]
    (println)
    (println (str prefix (str/join (repeat fill "─"))))))

(defn ^:private frame-bottom []
  (println (str/join (repeat frame-width "─"))))

;; ============================================================
;; Shared helpers
;; ============================================================

(defn ^:private top-level-cmd?
  "Is this command a top-level instruction key (path length = 1)?
   Filters out nested/internal commands like [:ACCOUNT-1-1 :from]."
  [cmd]
  (= 1 (count (cm/command-path cmd))))

(defn ^:private value-exists-at-path?
  [instr path]
  (not= ::missing (get-in instr path ::missing)))

(def ^:private pprint-width 120)

(defn ^:private pprint-wide
  [data]
  #?(:clj  (binding [pp/*print-right-margin* pprint-width
                      *print-namespace-maps* false]
             (pp/pprint data))
     :cljs (println (pr-str data))))


;; ============================================================
;; Command description (for pprint-debug modes)
;; ============================================================

(defn ^:private describe-driver
  [command-data]
  (let [raw (or (get command-data :=>) (get command-data "=>"))]
    (when raw
      (cond
        (keyword? raw) (name raw)
        (string? raw)  raw
        (vector? raw)
        (let [first-el (first raw)]
          (if (or (vector? first-el) (sequential? first-el))
            (str "pipe " (str/join " | " (map #(if (vector? %) (name (first %)) (name %)) raw)))
            (let [drv-name (if (string? first-el) first-el (name first-el))]
              (if (> (count raw) 1)
                (str drv-name " " (str/join " " (map pr-str (rest raw))))
                drv-name))))
        :else nil))))

(defn ^:private cmd-describe
  [command-data]
  (let [driver  (describe-driver command-data)
        base
        (cond
          (not (map? command-data))
          {:kind :data :label "data"}

          (contains? command-data :commando/mutation)
          (let [mut-name (name (:commando/mutation command-data))
                params   (cond-> []
                           (:percent command-data) (conj (str (:percent command-data) "%"))
                           (:factor command-data)  (conj (str "×" (:factor command-data))))]
            {:kind   :mutation
             :label  (str mut-name (when (seq params) (str " " (str/join " " params))))})

          (contains? command-data :commando/from)
          {:kind   :from
           :label  "from"
           :source (last (:commando/from command-data))}

          (contains? command-data :commando/context)
          {:kind :context :label "context"}

          (contains? command-data :commando/fn)
          (let [n (count (:args command-data []))]
            {:kind  :fn
             :label (str "fn" (when (pos? n) (str " ×" n " args")))})

          (contains? command-data :commando/apply)
          {:kind :apply :label "apply"}

          (contains? command-data :commando/macro)
          {:kind  :macro
           :label (str "macro:" (name (:commando/macro command-data)))}

          :else
          {:kind :data :label "data"})]
    (if driver
      (assoc base :driver driver)
      base)))

(defn ^:private format-driver-suffix
  [desc]
  (if-let [drv (:driver desc)]
    (str " => " drv)
    ""))

(defn ^:private format-path-name
  [path]
  (if (<= (count path) 1)
    (str (last path))
    (str/join "." (map str path))))

(defn ^:private format-node-label
  [key-name desc value show-value?]
  (let [drv (format-driver-suffix desc)]
    (if show-value?
      (case (:kind desc)
        :from (str key-name " ← " (:source desc) drv " = " (pr-str value))
        (str key-name " ‹" (:label desc) "›" drv " = " (pr-str value)))
      (case (:kind desc)
        :from (str key-name " ← " (:source desc) drv)
        (str key-name " ‹" (:label desc) "›" drv)))))

(defn ^:private format-node-label-no-value
  [key-name desc]
  (format-node-label key-name desc nil false))

;; ============================================================
;; Graph building
;; ============================================================

(defn ^:private resolve-top-deps
  "Given a command and its dependency map, resolve transitive deps
   to only include top-level commands."
  [cmd cm-dependency top-cmds]
  (loop [frontier (get cm-dependency cmd #{})
         visited  #{}
         result   #{}]
    (if (empty? frontier)
      result
      (let [node     (first frontier)
            frontier (disj frontier node)]
        (if (contains? visited node)
          (recur frontier visited result)
          (if (contains? top-cmds node)
            (recur frontier (conj visited node) (conj result node))
            (recur (into frontier (get cm-dependency node #{}))
                   (conj visited node)
                   result)))))))

(defn ^:private build-simplified-graph
  [cm-dependency cm-running-order]
  (let [all-cmds (set (keys cm-dependency))
        top-cmds (set (filter top-level-cmd? all-cmds))
        simplified-deps
        (into {} (map (fn [cmd] [cmd (resolve-top-deps cmd cm-dependency top-cmds)]) top-cmds))
        dependents
        (reduce-kv
          (fn [acc cmd cmd-deps]
            (reduce (fn [a dep] (update a dep (fnil conj #{}) cmd))
                    acc cmd-deps))
          {} simplified-deps)
        order-index (into {} (map-indexed (fn [i c] [c i]) cm-running-order))
        top-order   (sort-by #(get order-index % 999)
                             (filter top-level-cmd? cm-running-order))
        roots       (filterv #(empty? (get simplified-deps % #{})) top-order)
        sorted-dependents
        (reduce-kv
          (fn [acc parent children-set]
            (assoc acc parent (sort-by #(get order-index % 999) children-set)))
          {} dependents)]
    {:deps       simplified-deps
     :dependents sorted-dependents
     :roots      roots
     :order      (vec top-order)}))

(defn ^:private build-full-graph
  [cm-dependency cm-running-order]
  (let [non-root?  (fn [cmd] (seq (cm/command-path cmd)))
        visible    (set (filter non-root? (keys cm-dependency)))
        visible-deps
        (into {} (map (fn [cmd]
                        [cmd (set (filter visible (get cm-dependency cmd #{})))])
                      visible))
        dependents
        (reduce-kv
          (fn [acc cmd cmd-deps]
            (reduce (fn [a dep] (update a dep (fnil conj #{}) cmd))
                    acc cmd-deps))
          {} visible-deps)
        order-index (into {} (map-indexed (fn [i c] [c i]) cm-running-order))
        visible-order (sort-by #(get order-index % 999)
                               (filter non-root? cm-running-order))
        roots (filterv #(empty? (get visible-deps % #{})) visible-order)
        sorted-dependents
        (reduce-kv
          (fn [acc parent children-set]
            (assoc acc parent (sort-by #(get order-index % 999) children-set)))
          {} dependents)]
    {:deps       visible-deps
     :dependents sorted-dependents
     :roots      roots
     :order      (vec visible-order)}))

;; ============================================================
;; Mode: :tree
;; ============================================================

(defn ^:private pprint-tree
  [result original-instruction]
  (let [cm-dep   (:internal/cm-dependency result)
        cm-order (:internal/cm-running-order result)
        instr    (:instruction result)
        {:keys [roots dependents]} (build-full-graph cm-dep cm-order)
        printed  (atom #{})]
    (frame-top "Data Flow")
    (letfn [(get-orig [cmd] (get-in original-instruction (cm/command-path cmd)))
            (get-val  [cmd] (get-in instr (cm/command-path cmd)))
            (node-label [cmd]
              (let [path       (cm/command-path cmd)
                    orig       (get-orig cmd)
                    value      (get-val cmd)
                    desc       (cmd-describe (if (map? orig) orig {}))
                    has-value? (value-exists-at-path? instr path)
                    key-name   (format-path-name path)]
                (format-node-label key-name desc value has-value?)))
            (print-node [cmd prefix is-last]
              (let [connector (if is-last "└─► " "├─► ")
                    revisit?  (contains? @printed cmd)
                    label     (node-label cmd)]
                (if revisit?
                  (println (str "  " prefix connector label "  ⤶"))
                  (do
                    (swap! printed conj cmd)
                    (println (str "  " prefix connector label))
                    (let [kids       (get dependents cmd [])
                          new-prefix (str prefix (if is-last "    " "│   "))]
                      (doseq [[i child] (map-indexed vector kids)]
                        (print-node child new-prefix (= i (dec (count kids))))))))))]
      (let [rv (vec roots)]
        (doseq [[i root] (map-indexed vector rv)]
          (swap! printed conj root)
          (println (str "  " (node-label root)))
          (let [kids (get dependents root [])]
            (doseq [[j child] (map-indexed vector kids)]
              (print-node child "" (= j (dec (count kids))))))
          (when (< i (dec (count rv)))
            (println)))))
    (frame-bottom)))

;; ============================================================
;; Mode: :table
;; ============================================================

(defn ^:private pprint-table-mode
  [result original-instruction]
  (let [cm-dep   (:internal/cm-dependency result)
        cm-order (:internal/cm-running-order result)
        instr    (:instruction result)
        {:keys [deps order]} (build-full-graph cm-dep cm-order)
        rows (map-indexed
               (fn [i cmd]
                 (let [path       (cm/command-path cmd)
                       key-name   (format-path-name path)
                       orig       (get-in original-instruction path)
                       desc       (cmd-describe (if (map? orig) orig {}))
                       has-value? (value-exists-at-path? instr path)
                       value      (get-in instr path)
                       dep-keys   (get deps cmd #{})
                       meaningful-deps
                       (filter (fn [dep]
                                 (let [dp (cm/command-path dep)
                                       dep-orig (get-in original-instruction dp)]
                                   (or (top-level-cmd? dep)
                                       (and (map? dep-orig)
                                            (or (contains? dep-orig :commando/from)
                                                (contains? dep-orig "commando-from"))))))
                               dep-keys)
                       dep-str  (if (empty? meaningful-deps)
                                  "—"
                                  (str/join ", " (map #(format-path-name (cm/command-path %)) meaningful-deps)))]
                   {:n         (inc i)
                    :key       key-name
                    :type      (str (case (:kind desc)
                                      :from (str "← " (:source desc))
                                      (:label desc))
                                    (format-driver-suffix desc))
                    :deps      dep-str
                    :value     (if has-value? (pr-str value) "—")}))
               order)
        w-n    (count (str (count rows)))
        w-key  (apply max 4 (map (comp count :key) rows))
        w-type (apply max 4 (map (comp count :type) rows))
        w-deps (apply max 10 (map (comp count :deps) rows))
        w-val  (min 40 (apply max 5 (map (comp count :value) rows)))
        pad    (fn [s w] (str s (str/join (repeat (max 0 (- w (count s))) " "))))
        pad-r  (fn [s w] (str (str/join (repeat (max 0 (- w (count s))) " ")) s))
        hr     (fn [j] (str " " (str/join (repeat w-n "─"))
                            " " j " " (str/join (repeat w-key "─"))
                            " " j " " (str/join (repeat w-type "─"))
                            " " j " " (str/join (repeat w-deps "─"))
                            " " j " " (str/join (repeat w-val "─"))
                            " "))]
    (frame-top "Execution Map")
    (println (hr "┬"))
    (println (str " " (pad-r "#" w-n)
                  " │ " (pad "key" w-key)
                  " │ " (pad "type" w-type)
                  " │ " (pad "depends on" w-deps)
                  " │ " "value"))
    (println (hr "┼"))
    (doseq [row rows]
      (println (str " " (pad-r (str (:n row)) w-n)
                    " │ " (pad (:key row) w-key)
                    " │ " (pad (:type row) w-type)
                    " │ " (pad (:deps row) w-deps)
                    " │ " (let [v (:value row)]
                             (if (> (count v) w-val)
                               (str (subs v 0 (- w-val 1)) "…")
                               v)))))
    (println (hr "┴"))
    (frame-bottom)))

;; ============================================================
;; Mode: :graph
;; ============================================================

(defn ^:private pprint-graph-mode
  [result original-instruction]
  (let [cm-dep   (:internal/cm-dependency result)
        cm-order (:internal/cm-running-order result)
        {:keys [deps dependents roots order]} (build-simplified-graph cm-dep cm-order)
        sinks  (filterv (fn [cmd] (empty? (get dependents cmd []))) order)
        sink?  (set sinks)
        root?  (set roots)
        mid    (filterv (fn [cmd] (and (not (root? cmd)) (not (sink? cmd)))) order)
        fmt-key (fn [cmd] (str (last (cm/command-path cmd))))
        fmt-edges (fn [cmd]
                    (let [orig (get-in original-instruction (cm/command-path cmd))
                          desc (cmd-describe (if (map? orig) orig {}))
                          kids (get dependents cmd [])]
                      (str (format-node-label-no-value (fmt-key cmd) desc)
                           (when (seq kids)
                             (str " ──► " (str/join ", " (map fmt-key kids)))))))]
    (frame-top "Dependency Graph")
    (when (seq roots)
      (println "  Sources (no dependencies):")
      (doseq [r roots]
        (println (str "    " (fmt-edges r)))))
    (when (seq mid)
      (println)
      (println "  Transforms:")
      (doseq [m mid]
        (println (str "    " (fmt-edges m)))))
    (when (seq sinks)
      (println)
      (println "  Sinks (no dependents):")
      (println (str "    " (str/join ", " (map fmt-key sinks)))))
    (frame-bottom)))

;; ============================================================
;; Mode: :stats
;; ============================================================

(defn ^:private pprint-stats-mode
  [result _original-instruction]
  (let [order   (:internal/cm-running-order result)
        top-n   (count (filter top-level-cmd? order))
        total-n (count order)
        errors  (count (:errors result))
        stats   (:stats result)]
    (frame-top "Stats")
    (println (str "  Keys: " top-n " · Commands: " total-n " · Errors: " errors
                  " · Status: " (name (:status result))))
    (when (seq stats)
      (let [max-key-len (apply max 0 (map (comp count name first) stats))]
        (println)
        (doseq [[index [stat-key _ formatted]] (map-indexed vector stats)]
          (let [key-str (name stat-key)
                padding (str/join (repeat (- max-key-len (count key-str)) " "))]
            (println (str "  " (if (= "execute" key-str) "=" (str (inc index)))
                         "  " key-str " " padding formatted))))))
    (when (seq (:errors result))
      (println)
      (println "  Errors:")
      (doseq [err (:errors result)]
        (println (str "    · " (pr-str err)))))
    (frame-bottom)))

;; ============================================================
;; Mode: :instr-before / :instr-after
;; ============================================================

(defn ^:private pprint-instr-before
  [_result original-instruction]
  (frame-top "Instruction (before)")
  (pprint-wide original-instruction)
  (frame-bottom))

(defn ^:private pprint-instr-after
  [result _original-instruction]
  (frame-top "Instruction (after)")
  (pprint-wide (:instruction result))
  (frame-bottom))

;; ============================================================
;; pprint-debug / execute-debug
;; ============================================================

(defn ^:private pprint-single-mode
  [mode result original-instruction]
  (case mode
    :tree         (pprint-tree result original-instruction)
    :table        (pprint-table-mode result original-instruction)
    :graph        (pprint-graph-mode result original-instruction)
    :stats        (pprint-stats-mode result original-instruction)
    :instr-before (pprint-instr-before result original-instruction)
    :instr-after  (pprint-instr-after result original-instruction)
    (throw (ex-info (str "Unknown pprint-debug mode: " mode)
                    {:mode mode
                     :available [:tree :table :graph :stats :instr-before :instr-after]}))))

(defn ^:private pprint-debug
  "Pretty-print execution debug info.

   Accepts a single mode keyword or a vector of modes to combine.

   Modes:
     :table        — tabular execution map (order, deps, results) (default)
     :tree         — enriched data flow tree with values
     :graph        — compact dependency graph (structure only)
     :stats        — execution statistics (timing, counts, errors)
     :instr-before — wide pprint of the original instruction
     :instr-after  — wide pprint of the executed instruction

   Usage:
     (pprint-debug result instruction)
     (pprint-debug result instruction :table)
     (pprint-debug result instruction [:instr-before :table :stats])"
  ([result original-instruction]
   (pprint-debug result original-instruction :tree))
  ([result original-instruction mode]
   (if (vector? mode)
     (doseq [m mode]
       (pprint-single-mode m result original-instruction))
     (pprint-single-mode mode result original-instruction))))

(defn execute-debug
  "Execute an instruction with debug enabled, print with pprint-debug.
   Returns the execution result.

   Accepts a single mode keyword or a vector of modes.

   Usage:
     (execute-debug registry instruction)
     (execute-debug registry instruction :table)
     (execute-debug registry instruction [:instr-before :tree :stats])

   Example:
     (require '[commando.commands.builtin :as builtin])

     (execute-debug
       [builtin/command-from-spec]
       {:a 1 :b {:commando/from [:a] :=> [:fn inc]}}
       :table)"
  ([registry instruction]
   (execute-debug registry instruction :table))
  ([registry instruction mode]
   (let [result (binding [utils/*execute-config*
                          (assoc (utils/execute-config) :debug-result true)]
                  (commando/execute registry instruction))]
     (pprint-debug result instruction mode)
     result)))

;; ============================================================
;; execute-trace — trace nested execute calls
;; ============================================================
;;
;; Wraps an execution function and prints a tree of all nested
;; commando/execute calls with timing stats, instruction keys,
;; and optional titles.
;;
;; ============================================================

(defn ^:private trace-extract-children
  "Extract child execution entries from trace data.
   Children are map-valued entries that are not known metadata keys."
  [data]
  (into []
    (keep (fn [[k v]]
            (when (and (map? v)
                       (not (contains? #{:stats :instruction-keys :instruction-title} k)))
              [k v])))
    data))

(defn ^:private trace-format-keys
  "Format instruction keys for compact display."
  [keys-vec]
  (let [ks (keep (fn [k] (when-not (contains? #{"__title" :__title} k) (str k)))
                 keys-vec)]
    (when (seq ks)
      (let [display (take 5 ks)]
        (cond-> (str/join ", " display)
          (> (count ks) 5) (str ", …"))))))

(defn ^:private trace-format-summary
  "Format a compact one-line summary with only execute-commands! and execute times."
  [stats]
  (when (seq stats)
    (let [stats-map (into {} (map (fn [[k _ formatted]] [(name k) formatted]) stats))
          cmds-t    (get stats-map "execute-commands!")
          exec-t    (get stats-map "execute")]
      (cond
        (and cmds-t exec-t) (str "execute-commands! " cmds-t " · execute " exec-t)
        exec-t              (str "execute " exec-t)
        cmds-t              (str "execute-commands! " cmds-t)
        :else               nil))))

(defn ^:private trace-print-node
  "Print a child execution node with tree connectors."
  [uuid data prefix is-last]
  (let [short-id    (subs (str uuid) 0 (min 8 (count (str uuid))))
        title       (:instruction-title data)
        keys-str    (trace-format-keys (:instruction-keys data))
        label       (cond-> short-id
                      title (str " ‹" title "›"))
        connector   (if is-last "└─► " "├─► ")
        children    (trace-extract-children data)
        new-prefix  (str prefix (if is-last "    " "│   "))
        leaf?       (empty? children)
        body-prefix (str "  " new-prefix (if leaf? "  " "│ "))
        summary     (trace-format-summary (:stats data))]
    (println (str "  " prefix connector label))
    (when keys-str
      (println (str body-prefix keys-str)))
    (when summary
      (println (str body-prefix summary)))
    (let [cv (vec children)]
      (doseq [[i [cuuid cdata]] (map-indexed vector cv)]
        (trace-print-node cuuid cdata new-prefix (= i (dec (count cv))))))))

(defn ^:private trace-print
  "Print the full execution trace tree."
  [data]
  (let [roots (trace-extract-children data)]
    (frame-top "Execution Trace")
    (let [rv (vec roots)]
      (doseq [[i [uuid node-data]] (map-indexed vector rv)]
        (let [short-id (subs (str uuid) 0 (min 8 (count (str uuid))))
              title    (:instruction-title node-data)
              keys-str (trace-format-keys (:instruction-keys node-data))
              label    (cond-> short-id
                         title (str " ‹" title "›"))
              summary  (trace-format-summary (:stats node-data))
              children (trace-extract-children node-data)]
          (println (str "  " label))
          (when keys-str
            (println (str "  │ " keys-str)))
          (when summary
            (println (str "  │ " summary)))
          (let [cv (vec children)]
            (doseq [[j [cuuid cdata]] (map-indexed vector cv)]
              (trace-print-node cuuid cdata "" (= j (dec (count cv))))))
          (when (< i (dec (count rv)))
            (println)))))
    (frame-bottom)))

(defn execute-trace
  "Trace all nested commando/execute calls with timing.

   Takes a zero-argument function that calls commando/execute and
   returns its result unchanged. Prints a tree showing every execute
   invocation (including recursive calls from macros/mutations) with
   timing stats and instruction keys.

   Add :__title or \"__title\" to an instruction to label it in the trace.

   Usage:
     (execute-trace
       #(commando/execute registry instruction))"
  [execution-fn]
  (let [stats-state (atom {})
        result
        (binding [utils/*execute-config*
                  (assoc (utils/execute-config)
                    :hook-execute-start
                    (fn [e]
                      (swap! stats-state
                        (fn [s]
                          (update-in s (:stack utils/*execute-internals*)
                            #(merge % {:instruction-title
                                       (when (map? (:instruction e))
                                         (or (get (:instruction e) "__title")
                                           (get (:instruction e) :__title)))})))))
                    :hook-execute-end
                    (fn [e]
                      (swap! stats-state
                        (fn [s]
                          (update-in s (:stack utils/*execute-internals*)
                            #(merge % {:stats            (:stats e)
                                       :instruction-keys (when (map? (:instruction e))
                                                           (vec (keys (:instruction e))))}))))))]
          (execution-fn))]
    (trace-print @stats-state)
    result))

