(ns video-note-taker.access-shared)

(defn has-role? [users-roles needed-roles]
  (not (empty? (clojure.set/intersection (set users-roles) (set needed-roles)))))

(defn can-upload [users-roles] (has-role? users-roles #{"_admin" "family_lead" "business_user"}))
(defn can-delete-videos [users-roles] (has-role? users-roles #{"_admin" "family_lead" "business_user"}))
(defn can-change-video-display-name [users-roles] (has-role? users-roles #{"_admin" "family_lead" "business_user"}))
(defn can-change-video-share-settings [user-roles] (has-role? user-roles #{"_admin" "family_lead" "business_user"}))
(defn can-edit-others-notes [user-roles] (has-role? user-roles #{"_admin" "family_lead" "business_user"}))
(defn can-import-spreadsheet [user-roles] (has-role? user-roles #{"_admin" "spreadsheet_import"}))
(defn can-create-family-member-users [user-roles] (has-role? user-roles #{"_admin" "family_lead" "business_user"}))
(defn can-create-groups [user-roles] (has-role? user-roles #{"_admin" "family_lead" "business_user"}))
