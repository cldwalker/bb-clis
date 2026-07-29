# Instructions

## Checks required for all changes

All changes must pass:

- `clojure -M:clj-kondo --lint src test`
- `bb --config dev-bb.edn lint:large-vars`

## Additional checks for larger changes

For changes with more than 10 lines, the other two bb linters from `.github/workflows/test.yml` and the test suite must also pass:

- `bb --config dev-bb.edn lint:ns-docstrings`
- `bb --config dev-bb.edn lint:minimize-public-vars`
- `bb test`
