// Loads a non-minified TeaVM classes.js (a boot build, for example
// port/target/26.3/boot-dist/classes.js) into node and calls the compiled Java methods directly,
// so the TeaVM path can be checked against the golden fixtures like the wasm kernel is.
//
// TeaVM keeps methods as top-level `let` bindings or `$rt_java` properties named
// <package initials>_<Class>_<method>[n], where n tells overloads apart. A probe appended to the
// module closure evaluates names in that scope; overloads are picked by their parameter names.
import {readFileSync} from "node:fs";
import vm from "node:vm";

const PROBE = "$rt_exports.__gaiusProbe = (code) => eval(code);\n";

/**
 * Parameter names of a compiled arrow function, `$this` included, without TeaVM's decoration:
 * `$x` normally, `$_x` when the method assigns to the parameter.
 */
function parameters(fn) {
  const text = Function.prototype.toString.call(fn);
  const arrow = text.indexOf("=>");
  const head = text.slice(0, arrow).trim();
  const list = head.startsWith("(") ? head.slice(1, head.lastIndexOf(")")) : head;
  return list.split(",").map((name) => bare(name.trim())).filter(Boolean);
}

const bare = (name) => name.replace(/^\$_?/, "");

export function loadTeaVM(path) {
  const source = readFileSync(path, "utf8");
  const end = source.lastIndexOf("}));");
  if (end < 0) throw new Error(`${path} is not a TeaVM module`);
  const exportsObject = {};
  const module = vm.runInThisContext(
    `(function (exports) {${source.slice(0, end)}${PROBE}${source.slice(end)}\n})`, {filename: path});
  module(exportsObject);
  if (typeof exportsObject.__gaiusProbe !== "function") throw new Error(`${path}: the probe did not attach`);
  return new TeaVMArtifact(source, exportsObject.__gaiusProbe);
}

class TeaVMArtifact {
  constructor(source, evaluate) {
    this.source = source;
    this.evaluate = evaluate;
    this.cache = new Map();
    this.jstr = this.global("$rt_str");
    this.doubleArray = this.global("$rt_createDoubleArrayFromData");
  }

  /** A binding of the module scope, or undefined. */
  global(name) {
    try {
      return this.evaluate(`typeof ${name} === "undefined" ? undefined : ${name}`);
    } catch {
      return undefined;
    }
  }

  /** A static field (TeaVM keeps them as bindings or $rt_java properties); throws when unset. */
  staticField(name) {
    const value = this.global(name) ?? this.evaluate(`$rt_java[${JSON.stringify(name)}]`);
    if (value === undefined || value === null) throw new Error(`static field ${name} is not set`);
    return value;
  }

  /** Every compiled function named exactly `name`: the `let` binding and the $rt_java property. */
  named(name) {
    const found = [this.global(name), this.evaluate(`$rt_java[${JSON.stringify(name)}]`)];
    return [...new Set(found.filter((fn) => typeof fn === "function"))];
  }

  /**
   * Overloads of `prefix` (prefix, prefix0, prefix1, ...) whose parameters are `names`
   * (`$this` included for instance methods), optionally narrowed by a test on their source.
   */
  overloads(prefix, names, test = () => true) {
    const key = `${prefix}(${names})`;
    let all = this.cache.get(key);
    if (!all) {
      all = [];
      for (const suffix of ["", ...Array.from({length: 24}, (_, i) => String(i))]) {
        for (const fn of this.named(prefix + suffix)) {
          if (parameters(fn).join(",") === names.map(bare).join(",")) all.push(fn);
        }
      }
      this.cache.set(key, all);
    }
    return all.filter((fn) => test(Function.prototype.toString.call(fn)));
  }

  /** The single overload of `prefix` with parameters `names`; throws when none or several match. */
  method(prefix, names, test) {
    const found = this.overloads(prefix, names, test);
    if (found.length !== 1) throw new Error(`${prefix}(${names}): ${found.length} compiled overloads match`);
    return found[0];
  }

  /** A class constructor (`new` allocates without running a Java constructor). */
  type(name) {
    const found = this.named(name);
    if (found.length === 0) throw new Error(`class ${name} is not in the artifact`);
    return found[0];
  }

  /** Allocates `className` and runs the initializer with parameters `names` on it. */
  construct(className, names, args, test) {
    const init = this.method(`${className}__init_`, ["$this", ...names], test);
    const object = new (this.type(className))();
    init(object, ...args);
    return object;
  }

  /** The virtual method name TeaVM gives the method that `impl` implements, e.g. "$get36". */
  virtualName(impl) {
    let name = this.cache.get(`virtual:${impl}`);
    if (!name) {
      const pattern = new RegExp(`"(\\$[A-Za-z0-9_$]+)", \\$rt_wrapFunction\\d+\\((?:\\$rt_java\\.)?${
        impl.replaceAll("$", "\\$")}\\)`);
      const match = pattern.exec(this.source);
      if (!match) throw new Error(`no virtual method is implemented by ${impl}`);
      name = match[1];
      this.cache.set(`virtual:${impl}`, name);
    }
    return name;
  }

  long(decimal) {
    return BigInt.asIntN(64, BigInt(decimal));
  }

  doubles(values) {
    return this.doubleArray(Array.from(values));
  }
}
