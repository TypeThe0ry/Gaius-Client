export function quitTargetDisappearance({closedTarget, barrierComplete, stoppedPageObserved, crash}) {
  if (crash) return {accepted: false, reason: `renderer crashed: ${crash}`};
  if (stoppedPageObserved) return {accepted: true, reason: closedTarget
    ? "stopped page observed before target disappearance"
    : "stopped page observed while terminal document remained available"};
  if (!closedTarget) return {accepted: false, reason: "target did not disappear"};
  if (barrierComplete) return {accepted: true, reason: "quit barrier completion observed before target disappearance"};
  return {accepted: false, reason: "target disappeared without quit completion or stopped-page evidence"};
}
