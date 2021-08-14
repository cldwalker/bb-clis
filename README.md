## Description

An assortment of handy [Babashka](https://github.com/borkdude/babashka) CLIs and tasks. bin scripts strive to be compatible with Clojure.

## Setup

First, [install babashka](https://github.com/borkdude/babashka#installation).
These scripts require babashka >= 0.5.0.

To run scripts from within bb-clis, read the [Local setup](#local).
To run scripts from any directory, read the [Global setup](#global). To install individual scripts, read the [Single Script setup](#single-script). To setup babashka as I use it, read the [bb section](#bb).

### Local

```sh
$ git clone https://github.com/cldwalker/bb-clis
$ cd bb-clis
```

Run any script by referencing its local path e.g. `bin/bb-ns-dep-tree src/cldwalker/bb-clis/util.clj`. Most usage sections below assume you're running from any directory but you can simply change substitute the local path e.g. `bb-ns-dep-tree` -> `bin/bb-ns-dep-tree`.

### Global

This section is for those who understand how [bb's classpath](https://book.babashka.org/#_classpath) works and are ok with the tradeoff of setting a global $BABASHKA_CLASSPATH in exchange for being able to run these executables from any directory.

Setup your $PATH and $BABASHKA_CLASSPATH:
```sh
$ git clone https://github.com/cldwalker/bb-clis
$ cd bb-clis

$ export PATH=$PATH:$HOME/path/to/bb-clis/bin

# To be able to use the scripts outside of this repository
$ export BABASHKA_CLASSPATH=$BABASHKA_CLASSPATH:$HOME/path/to/bb-clis/src
```

### Single Script

To use a single babashka script without the General setup, simply export one with `bb uberscript`:
```sh
# -f can be any of my babashka scripts that don't have options
$ bb uberscript my-bb-github-repo.clj -f bin/bb-github-repo

# To run the exported script
$ bb my-bb-github-repo.clj

# Optionally, use https://github.com/borkdude/carve to delete unused code
$ clojure -A:carve --opts '{:paths ["my-bb-github-repo.clj"] :interactive? false}'
```

Note: uberscript doesn't work well with dynamic requires e.g. pods. Use [bb uberjar](https://book.babashka.org/#uberjar) for those cases.

### bb

I use the [global setup](#global) as I run these scripts from any directory.

Babashka supports `$BABASHA_PRELOADS` which allows arbitrary clojure to be run at the start of each invocation. This is handy for loading one's preferred set of vars and namespaces, especially when paired with an alias. For example, `alias bbp="BABASHKA_PRELOADS='(load-file (str (System/getenv \"HOME\") \"/path/to/this-repo/preloads.clj\"))' bb"`

Preloaded fns like `map-keys` are then available on the commandline:

```sh
# Prints out env variables map with env keys converted to clojure-cased keywords
$ bbp '(->> (System/getenv) (into {}) (map-keys #(-> % (str/replace "_" "-") str/lower-case keyword)))'
{:gopath "/Users/me/.go", :path ...}
```
## Tasks

bb.edn contains global tasks i.e. tasks that are useful in any directory or project. These tasks are usually small and if they get bigger, they become a CLI. To run these tasks from any directory, I use this shell function:

```sh
function bbg() { BABASHKA_EDN=/path/to/this-repo/bb.edn bb $@ }
```

For example, `bbg tasks`.

## CLIs

The following CLIs are under bin/:

* [bb-github-pr-for-commit](#bb-github-pr-for-commit)
* [bb-github-repo](#bb-github-repo)
* [bb-grep-result-frequencies](#bb-grep-result-frequencies)
* [bb-update-lein-dependency](#bb-update-lein-dependency)
* [bb-table](#bb-table)
* [bb-project-clj](#bb-project-clj)
* [bb-replace](#bb-replace)
* [bb-vis](#bb-vis)
* [bb-try](#bb-try)
* [bb-ns-dep-tree](#bb-ns-dep-tree)

### bb-github-pr-for-commit

Prints github url of PR associated with a commit. It assumes a current directory's repository but any repository can be specified. See https://github.com/mislav/hub-api-utils/blob/master/bin/hub-pr-with-commit for an alternative implementation.

#### Setup

* Optional: To have this executable with private repositories, set `$GITHUB_USER` to your user and [create and set a $GITHUB_OAUTH_TOKEN](https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/#non-web-application-flow)

#### Usage

```sh
# Prints url and opens url for osx
$ ./bb-github-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb
"https://github.com/atom/atom/pull/20350"

# Open url of current github repository
$ ./bb-github-pr-for-commit SHA
```

### bb-github-repo

For the current github repo, open different repo urls e.g. commit or branch. Inspired by [this ruby version](https://github.com/cldwalker/irbfiles/blob/1fb97d84bcdf491325176d08e386468b12ece738/boson/commands/public/url/github.rb#L20-L50).

To open a commit, `bb-github-repo -c SHA`.

### bb-grep-result-frequencies

For use with grep command to group results by frequency e.g. `git grep protocol | bb-group-grep-results`.

### bb-update-lein-dependency

Updates lein dependency of specified directories and optionally commits and pushes the change. For example, if I'm in the dependency's directory and I want to update two dependent projects to use its latest SHA, commit and git push:

`bb-update-lein-dependency -c -d ../proj1 -d ../proj2 my-dep $(git rev-parse HEAD)`.

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
$ bb-replace node-version 2.1.1

# A one-off regex can be used. This updates a map entry to false
$ bb-replace -f project.clj -F '$1 %s' "(:pseudo-names)\s+\w+" false
```

## bb-vis
Generates [vega-lite](https://vega.github.io/vega-lite/) visualizations given vega-lite data as a file or on stdin. Data file can be json or edn.

### Setup
Install vega-lite cmds with `yarn global add vega-lite --peer && yarn global add
canvas`. This was last confirmed to work with vega-lite 5.1.0 and canvas 2.8.0.

### Usage

Assume you have the following bar.edn:

```clj
{:data
  {:values
   [{:a "A", :b 28}
    {:a "B", :b 55}
    {:a "C", :b 43}
    {:a "D", :b 91}
    {:a "E", :b 81}
    {:a "F", :b 53}
    {:a "G", :b 19}
    {:a "H", :b 87}
    {:a "I", :b 52}]},
  :mark "bar",
  :encoding
  {:x {:field "a", :type "ordinal", :axis {:labelAngle 0}},
   :y {:field "b", :type "quantitative"}}}
```

To generate this image and open it: `bb-vis bar.edn -o`.

To generate this image as a pdf and open it: `bb-vis bar.edn -F pdf -o`

Generated image:

![this](images/bar.png)

Any of the [official examples](https://vega.github.io/vega-lite/examples/) can be generated by simply copying and pasting the data to stdin. For example, let's try the [2D histogram heatmap](https://vega.github.io/vega-lite/examples/rect_binned_heatmap.html):

```sh
cat <<EOF | bb-vis -o
{
  "$schema": "https://vega.github.io/schema/vega-lite/v4.json",
  "data": {"url": "data/movies.json"},
  "transform": [{
    "filter": {"and": [
      {"field": "IMDB_Rating", "valid": true},
      {"field": "Rotten_Tomatoes_Rating", "valid": true}
    ]}
  }],
  "mark": "rect",
  "width": 300,
  "height": 200,
  "encoding": {
    "x": {
      "bin": {"maxbins":60},
      "field": "IMDB_Rating",
      "type": "quantitative"
    },
    "y": {
      "bin": {"maxbins": 40},
      "field": "Rotten_Tomatoes_Rating",
      "type": "quantitative"
    },
    "color": {
      "aggregate": "count",
      "type": "quantitative"
    }
  },
  "config": {
    "view": {
      "stroke": "transparent"
    }
  }
}
EOF
```

Generated image:

![this](images/2d-histogram-heatmap.png)

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

## Development

Code is organized as follows:
* `src/cldwalker/bb-clis/babashka/` - Namespaces that only run within babashka
* `src/cldwalker/bb-clis/util/` - Namespaces that are useful to any clojure or bb clis

## License
See LICENSE.md

## Additional Links

* For more bb setup and aliases, see [my dotfiles repo](https://github.com/cldwalker/dotfiles/search?q=bb&unscoped_q=bb)
* See https://github.com/borkdude/babashka/blob/master/doc/examples.md for additional babashka cmd examples
