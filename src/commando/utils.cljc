(ns commando.utils
  "Public utility helpers for building on top of `commando.core/execute`."
  (:require
   [commando.impl.command-map :as cm]
   [commando.impl.status-map  :as smap]))

(defn hook-reject-commands-fn
  "Convenience helper for building a `:hook-command-guard-*-fn` — walks
   `:internal/cm-list` (already computed by step-find-commands, no re-traversal)
   and calls `pred` with `{:command-type :path :value}` for every found
   command. `pred` returns nil to allow the command through, or an error
   map (passed as-is to `status-map-handle-error`, so it's entirely up to
   the caller what shape/message it carries) to reject it. All commands are
   checked — a rejection does not stop the walk, so every violation ends up
   in `:errors`.

   Example
     (commando/execute
      [...registry...]
      {...instruction...}
      {:hook-command-guard-outer-fn
       (fn [status-map]
         (commando.utils/hook-reject-commands-fn status-map
           (fn [{:keys [command-type value]}]
             (when (and (= command-type :commando/resolve) (private? value))
               {:message \"resolver is private, direct client access is not allowed\"
                :error {:resolve-key value}}))))}"
  [status-map pred]
  (reduce
    (fn [sm command]
      (let [path  (cm/command-path command)
            value (get-in (:instruction sm) path)]
        (if-let [error (pred {:command-type (:type (cm/command-data command))
                              :path path
                              :value value})]
          (smap/status-map-handle-error sm error)
          sm)))
    status-map
    (:internal/cm-list status-map)))
