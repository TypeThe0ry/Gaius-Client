// Runs sections of the suite's TeaVM JavaScript build in node:
//   node run-js.cjs <classes.js> <section>...
// classes.js is a UMD module whose main(args) runs dev.gaius.classlib.Main. Output is the
// program's System.out (console.log). An uncaught error prints "UNCAUGHT <text>" and exits 3.
"use strict";
const [classes, ...sections] = process.argv.slice(2);
process.on("uncaughtException", error => {
  console.log("UNCAUGHT " + String(error && error.stack || error).split("\n").slice(0, 8).join(" | "));
  process.exit(3);
});
process.on("unhandledRejection", error => {
  console.log("UNCAUGHT " + String(error && error.stack || error).split("\n").slice(0, 8).join(" | "));
  process.exit(3);
});
const teavm = require(classes);
teavm.main(sections);
