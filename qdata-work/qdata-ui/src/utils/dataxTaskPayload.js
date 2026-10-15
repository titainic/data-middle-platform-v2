// Keep references and fields used to reopen the canvas. Connection configuration
// is resolved by the server, never supplied by the task editor.
const referenceFields = [
  'datasourceId', 'id', 'datasource_id', 'datasourceType', 'datasourceName',
  'dbname', 'databaseName', 'target_asset_id'
]

export function sanitizeDataXTaskPayload(data) {
  const taskType = data.taskType || data.actuator || (
    typeof data.draftJson === 'string' && data.draftJson
      ? JSON.parse(data.draftJson)?.taskType : data.draftJson?.taskType
  )
  if (taskType !== 'DATAX' || data.taskDefinitionList == null) return data

  const serialized = typeof data.taskDefinitionList === 'string'
  const definitions = serialized ? JSON.parse(data.taskDefinitionList) : data.taskDefinitionList
  const sanitized = definitions.map(node => {
    if (!node.taskParams) return node
    const taskParams = { ...node.taskParams }
    for (const key of ['datasources', 'readerDatasource', 'writerDatasource']) {
      const source = taskParams[key]
      if (!source) continue
      taskParams[key] = Object.fromEntries(referenceFields
        .filter(field => Object.hasOwn(source, field))
        .map(field => [field, source[field]]))
    }
    return { ...node, taskParams }
  })
  return { ...data, taskDefinitionList: serialized ? JSON.stringify(sanitized) : sanitized }
}
