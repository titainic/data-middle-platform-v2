export function mergeProjectSelection(current, visible, selected) {
  const visibleIds = new Set(visible.map(row => row.id));
  return [
    ...current.filter(row => !visibleIds.has(row.projectId)),
    ...selected.map(row => ({ projectId: row.id, projectCode: row.code, projectName: row.name }))
  ];
}
