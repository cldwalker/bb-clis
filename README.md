## Description

An assortment of handy [Babashka](https://github.com/borkdude/babashka) CLIs

## Setup

To setup using and modifying these scripts, follow the [General section](#general). To just install one script, follow the [Single Script section](#single-script). To setup babashka as I use it, follow the [bb section](#bb).

### General
Setup your $PATH and $BABASHKA_CLASSPATH:
```sh
$ git clone https://github.com/cldwalker/bb-clis
$ cd bb-clis

$ export PATH=$PATH:$PWD/bin
$ export BABASHKA_CLASSPATH=$PWD/src
# To last outside this shell, put the above in your rc file, replacing $PWD
# with the path to this repo
```

Then [install babashka](https://github.com/borkdude/babashka#installation).
These scripts require babashka >= 0.0.89.

### Single Script

To use a single babashka script without the General setup, simply export one using --uberscript:
```sh
# -f can be any of my babashka scripts that don't have options
$ bb -f clj-github-repo --uberscript my-clj-github-repo.clj

# To run the exported script
$ bb -f my-clj-github-repo.clj

# Optionally, use https://github.com/borkdude/carve to delete unused code
$ clojure -A:carve --opts '{:paths ["my-clj-github-repo.clj"] :interactive? false}'
```

### bb

The previous sections are about setting up your environment to run the scripts in this repository. This section is about running babashka on the commandline. This section assumes you've setup `$BABASHKA_CLASSPATH` as mentioned above.

Babashka supports `$BABASHA_PRELOADS` which allows arbitrary clojure to be run at the start of each invocation. This is handy for loading one's preferred set of vars and namespaces. To avoid loading [preloads](preloads.clj) when running babashka scripts but load them for commandline babashka, use this alias: `alias bb="BABASHKA_PRELOADS='(load-file (str (System/getenv \"HOME\") \"/path/to/this-repo/preloads.clj\"))' bb"`

Preloaded fns like `map-keys` are then available on the commandline:

```sh
# Prints out env variables map with env keys converted to clojure-cased keywords
$ bb '(->> (System/getenv) (into {}) (map-keys #(-> % (str/replace "_" "-") str/lower-case keyword)))'
{:gopath "/Users/me/.go", :path ...}
```

## CLIs

The following CLIs are under bin/:

* [clj-github-pr-for-commit](#clj-github-pr-for-commit)
* [clj-github-repo](#clj-github-repo)
* [clj-grep-result-frequencies](#clj-grep-result-frequencies)
* [clj-update-lein-dependency](#clj-update-lein-dependency)
* [clj-table](#clj-table)
* [clj-project-clj](#clj-project-clj)

### clj-github-pr-for-commit

Prints github url of PR associated with a commit. It assumes a current directory's repository but any repository can be specified. See https://github.com/mislav/hub-api-utils/blob/master/bin/hub-pr-with-commit for an alternative implementation.

#### Setup

* Install [clj-http-lite](https://github.com/borkdude/babashka#clj-http-lite) but with a slight tweak:
  ```sh
  CLJ_HTTP_LITE_PATH="$(clojure -Sdeps '{:deps {clj-http-lite {:git/url "https://github.com/borkdude/clj-http-lite" :sha "f44ebe45446f0f44f2b73761d102af3da6d0a13e"}}}' -Spath)"
  # This assumes you have followed the setup above
  export BABASHKA_CLASSPATH=$BABASHKA_CLASSPATH:$CLJ_HTTP_LITE_PATH
  ```
* Optional: To have this executable with private repositories, set `$GITHUB_USER` to your user and [create and set a $GITHUB_OAUTH_TOKEN](https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/#non-web-application-flow)

#### Usage

```sh
# Prints url and opens url for osx
$ ./clj-github-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb
"https://github.com/atom/atom/pull/20350"

# Open url of current github repository
$ ./clj-github-pr-for-commit SHA
```

### clj-github-repo

For the current github repo, open different repo urls e.g. commit or branch. Inspired by [this ruby version](https://github.com/cldwalker/irbfiles/blob/1fb97d84bcdf491325176d08e386468b12ece738/boson/commands/public/url/github.rb#L20-L50).

To open a commit, `clj-github-repo -c SHA`.

### clj-grep-result-frequencies

For use with grep command to group results by frequency e.g. `git grep protocol | clj-group-grep-results`.

### clj-update-lein-dependency

Updates lein dependency of specified directories and optionally commits and pushes the change. For example, if I'm in the dependency's directory and I want to update two dependent projects to use its latest SHA, commit and git push:

`clj-update-lein-dependency -c -d ../proj1 -d ../proj2 my-dep $(git rev-parse HEAD)`.

### clj-table
Prints an ascii table given an EDN collection on stdin or as a file:

```sh
$ echo '[{:a 4 :b 2} {:a 2 :c 3}]' | clj-table

| :a | :b | :c |
|----+----+----|
|  4 |  2 |    |
|  2 |    |  3 |

$ clj-table something.edn
...
```

### clj-project-clj
Prints a project.clj defproject form as a map. Useful for manipulating this data on the commandline

```sh
# Pretty prints a project's dependencies
$ clj-project-clj -d 1 | bb -I '(-> *input* first :dependencies clojure.pprint/pprint)'
```

## Additional Links

* For more bb setup and aliases, see [my dotfiles repo](https://github.com/cldwalker/dotfiles/search?q=bb&unscoped_q=bb)
* See https://github.com/borkdude/babashka/blob/master/doc/examples.md for additional babashka cmd examples
