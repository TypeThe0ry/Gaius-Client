"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _FDBKeyRange = _interopRequireDefault(require("../FDBKeyRange.js"));
var _cmp = require("./cmp.js");
var _binarySearchTree = _interopRequireDefault(require("./binarySearchTree.js"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
class RecordStore {
  constructor(keysAreUnique) {
    this.keysAreUnique = keysAreUnique;
    this.records = new _binarySearchTree.default(this.keysAreUnique);
  }
  get(key) {
    const range = key instanceof _FDBKeyRange.default ? key : _FDBKeyRange.default.only(key);
    return this.records.getRecords(range).next().value;
  }

  /**
   * Put a new record, and return the overwritten record if an overwrite occurred.
   * @param newRecord
   * @param noOverwrite - throw a ConstraintError in case of overwrite
   */
  put(newRecord, noOverwrite = false) {
    return this.records.put(newRecord, noOverwrite);
  }
  delete(key) {
    const range = key instanceof _FDBKeyRange.default ? key : _FDBKeyRange.default.only(key);
    const deletedRecords = [...this.records.getRecords(range)];
    for (const record of deletedRecords) {
      this.records.delete(record);
    }
    return deletedRecords;
  }

  /**
   * Delete the record with this key and this value, if there is one. Used by indexes, where the value is the primary
   * key of the object store record that the index record points to.
   */
  deleteByKeyAndValue(key, value) {
    // In a non-unique store the value is part of the sort order, so this finds the exact record. In a unique store
    // it finds the only record with this key, which may point at a different primary key.
    const record = this.records.get({
      key,
      value
    });
    if (record !== undefined && (0, _cmp.cmpKeys)(record.value, value) === 0) {
      this.records.delete(record);
    }
  }
  clear() {
    const deletedRecords = [...this.records.getAllRecords()];
    this.records = new _binarySearchTree.default(this.keysAreUnique);
    return deletedRecords;
  }
  values(range, direction = "next") {
    const descending = direction === "prev" || direction === "prevunique";
    const records = range ? this.records.getRecords(range, descending) : this.records.getAllRecords(descending);
    return {
      [Symbol.iterator]: () => {
        const next = () => {
          return records.next();
        };
        if (direction === "next" || direction === "prev") {
          return {
            next
          };
        }

        // For nextunique/prevunique, return an iterator that skips seen values
        // Note that we must return the _lowest_ value regardless of direction:
        // > Iterating with "prevunique" visits the same records that "nextunique"
        // > visits, but in reverse order.
        // https://w3c.github.io/IndexedDB/#dom-idbcursordirection-prevunique
        if (direction === "nextunique") {
          let previousValue = undefined;
          return {
            next: () => {
              let current = next();
              // for nextunique, continue if we already emitted the lowest unique value
              while (!current.done && previousValue !== undefined && (0, _cmp.cmpKeys)(previousValue.key, current.value.key) === 0) {
                current = next();
              }
              previousValue = current.value;
              return current;
            }
          };
        }

        // prevunique is a bit more complex due to needing to check the next value, which
        // invokes the iterable, so we need to keep a buffer of one "lookahead" result
        let current = next();
        let nextResult = next();
        return {
          next: () => {
            while (!nextResult.done && (0, _cmp.cmpKeys)(current.value.key, nextResult.value.key) === 0) {
              // note we return the _lowest_ possible value, hence set the current
              current = nextResult;
              nextResult = next();
            }
            const result = current;
            current = nextResult;
            nextResult = next();
            return result;
          }
        };
      }
    };
  }
  size() {
    return this.records.size();
  }
}
var _default = exports.default = RecordStore;
module.exports = exports.default;