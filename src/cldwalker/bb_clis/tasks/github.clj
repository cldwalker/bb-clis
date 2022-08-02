(ns cldwalker.bb-clis.tasks.github
  (:require [babashka.process :refer [shell]]))

(defn checkout-pr-branch
  "Check out a contributor's branch with write permissions.
Copy this from a github PR by looking for the branch after `Add more commits by pushing`"
  [pr-branch]
  (let [[_ user repo branch :as matches]
        (re-find #"https://github.com/(\S+)/(\S+)/tree/(.*)$" pr-branch)]
    (assert matches "Branch url with correct format required")
    (shell "git remote add" user (format "git@github.com:%s/%s" user repo))
    (shell "git fetch" user)
    (shell "git checkout -b" branch "-t" (str user "/" branch))))
