
## Scripts

The following scripts are under bin/:

* [gh-pr-for-commit](#gh-pr-for-commit)
* [gh-repo](#gh-repo)
* [bb-table](#bb-table)
* [bb-project-clj](#bb-project-clj)
* [bb-replace](#bb-replace)
* [bb-try](#bb-try)
* [bb-ns-dep-tree](#bb-ns-dep-tree)
* [bb-update-lein-dependency](#bb-update-lein-dependency)
* [bb-logseq-convert](#bb-logseq-convert)
* [bb-logseq-move-to-page](#bb-logseq-move-to-page)

### gh-pr-for-commit

Prints github url of PR associated with a commit. It assumes a current directory's repository but any repository can be specified. See https://github.com/mislav/hub-api-utils/blob/master/bin/hub-pr-with-commit for an alternative implementation.

#### Setup

* Optional: To have this executable with private repositories, set `$GITHUB_USER` to your user and [create and set a $GITHUB_OAUTH_TOKEN](https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/#non-web-application-flow)

#### Usage

```sh
# Prints url and opens url for osx
$ ./gh-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb
"https://github.com/atom/atom/pull/20350"

# Open url of current github repository
$ ./gh-pr-for-commit SHA
```

### gh-repo

For the current github repo, open different repo urls e.g. commit or branch. Inspired by [this ruby version](https://github.com/cldwalker/irbfiles/blob/1fb97d84bcdf491325176d08e386468b12ece738/boson/commands/public/url/github.rb#L20-L50).

To open a commit, `gh-repo -c SHA`.

### bb-table

#### Usage
Prints an ascii table given an EDN collection on stdin or as a file:

```sh
$ echo '[{:a 4 :b 2} {:a 2 :c 3}]' | bb-table

| :a | :b | :c |
|----+----+----|
|  4 |  2 |    |
|  2 |    |  3 |

$ bb-table -f something.edn
...
```

### bb-project-clj
Prints a project.clj defproject form as a map. Useful for manipulating this data on the commandline

```sh
# Pretty prints a project's dependencies
$ bb-project-clj -d 1 | bb -I '(-> *input* first :dependencies clojure.pprint/pprint)'
```

### bb-replace
Replaces a substring in a file using a regex to match it. Much less powerful
than sed but more user friendly as it supports configuring and naming regexs.
bb-replace reads configs found in ~/.bb-replace.edn and ./bb-replace.edn. See
script for documentation on config format.

```sh
# Use the default name replacements provided
$ cp .bb-replace.edn ~/.bb-replace.edn

# Navigate to a lein project and update project's version
$ bb-replace lein-version 1.2.1

# Navigate to a nodejs project and update project's version
$ bb-replace json-version 2.1.1

# A one-off regex can be used. This updates a map entry to false
$ bb-replace -f project.clj -F '$1 %s' "(:pseudo-names)\s+\w+" false
```

## bb-try

Try a Clojure library easily with bb. Inspired by https://github.com/avescodes/lein-try.

```sh
# Adds latest version of this library to classpath and starts repl
$ bb-try camel-snake-kebab

# Any additional arguments are passed on to bb
$ bb-try camel-snake-kebab "(require '[camel-snake-kebab.core :as csk]) (csk/->SCREAMING_SNAKE_CASE :babashka-classpath)"
:BABASHKA_CLASSPATH
```

Currently it only fetches the latest version of a library but I'm thinking of making a version optional.

## bb-ns-dep-tree

Print the ns dependency tree for a given ns or file. For example, if we want to
print the dependencies of
[datascript.datafy](https://github.com/tonsky/datascript/blob/master/src/datascript/datafy.cljc):

```sh
# On a local checkout of datascript
$ bb-ns-dep-tree src/datascript/datafy.cljc
datascript.datafy
├── clojure.core.protocols
├── datascript.pull-api
│   ├── datascript.db ...
│   └── datascript.pull-parser
│       └── datascript.db ...
├── datascript.db
│   ├── clojure.walk
│   ├── clojure.data
│   ├── me.tonsky.persistent-sorted-set
│   └── me.tonsky.persistent-sorted-set.arrays
└── datascript.impl.entity
    ├── clojure.core
    └── datascript.db ...

# We can also print the cljs dependencies of the same ns
$ bb-ns-dep-tree -l cljs datascript.datafy
datascript.datafy
├── clojure.core.protocols
├── datascript.pull-api
│   ├── datascript.db ...
│   └── datascript.pull-parser
│       └── datascript.db ...
├── datascript.db
│   ├── goog.array
│   ├── clojure.walk
│   ├── clojure.data
│   ├── me.tonsky.persistent-sorted-set
│   ├── me.tonsky.persistent-sorted-set.arrays
│   └── datascript.db ...
└── datascript.impl.entity
    ├── cljs.core
    └── datascript.db ...
```

### bb-update-lein-dependency

Updates lein dependency of specified directories and optionally commits and pushes the change. For example, if I'm in the dependency's directory and I want to update two dependent projects to use its latest SHA, commit and git push:

`bb-update-lein-dependency -c -d ../proj1 -d ../proj2 my-dep $(git rev-parse HEAD)`.

### bb-logseq-convert

Given a url, returns auto populated properties as a logseq block. The properties
are derived from the url's rdf data and what is converted to the logseq block is
highly configurable.

### bb-logseq-move-to-page

Given a logseq text block with a name property, moves that block to a logseq page.

## Logseq scripts

Scripts starting with `bb-logseq-` are a group of scripts for use with
[logseq](https://logseq.com/). My config of these scripts are in [this
directory](https://github.com/cldwalker/dotfiles/tree/master/.bb-logseq).
