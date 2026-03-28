(ns commando.impl.pathtrie
  (:require
   [commando.impl.command-map :as cm]))

(defn trie-insert-command!
  "Inserts a command into a trie with transient root."
  [trie! cmd]
  (let [path (cm/command-path cmd)]
    (if (empty? path)
      (assoc! trie! ::command cmd)
      (let [k (first path)
            sub (get trie! k {})]
        (if (= (count path) 1)
          (assoc! trie! k (assoc sub ::command cmd))
          (let [rest-keys (subvec (conj path ::command) 1)]
            (assoc! trie! k (assoc-in sub rest-keys cmd))))))))

(defn build-path-trie
  "Builds a trie from a collection of CommandMapPath objects.
   Uses transient root for efficient bulk construction."
  [cm-list]
  (persistent!
    (reduce trie-insert-command! (transient {}) cm-list)))

(defn trie-remove-paths
  "Removes all entries from a trie whose paths start with the given prefixes."
  [trie paths-to-remove]
  (reduce
    (fn [t path]
      (if (seq path)
        (let [parent-path (butlast path)
              leaf (last path)]
          (if (seq parent-path)
            (update-in t (vec parent-path) dissoc leaf)
            (dissoc t leaf)))
        {}))
    trie
    paths-to-remove))

(defn trie-insert-commands
  "Inserts commands into an existing trie."
  [trie new-commands]
  (reduce (fn [trie cmd]
            (assoc-in trie (conj (cm/command-path cmd) ::command) cmd))
    trie new-commands))
