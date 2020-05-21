(ns video-note-taker.access-shared)

(defn has-role? [users-roles needed-roles]
  (not (empty? (clojure.set/intersection (set users-roles) (set needed-roles)))))

(defn can-upload [users-roles] (has-role? users-roles #{"_admin" "family_lead" "business_user"}))
(defn can-delete-videos [users-roles] (has-role? users-roles #{"_admin" "family_lead" "business_user"}))
