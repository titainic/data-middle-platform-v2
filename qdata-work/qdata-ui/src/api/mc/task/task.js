import request from '@/utils/request'

// Query collection task list
export function listTask(query) {
  return request({
    url: '/mc/task/list',
    method: 'get',
    params: query
  })
}

// Query collection task details
export function getTask(id) {
  return request({
    url: '/mc/task/' + id,
    method: 'get'
  })
}

// Add new collection tasks
export function addTask(data) {
  return request({
    url: '/mc/task',
    method: 'post',
    data: data
  })
}

// Modify collection tasks
export function updateTask(data) {
  return request({
    url: '/mc/task',
    method: 'put',
    data: data
  })
}

// Delete collection task
export function delTask(id) {
  return request({
    url: '/mc/task/' + id,
    method: 'delete'
  })
}

// Get collection range
export function getRealtimeMcTaskScopeList(id) {
  return request({
    url: '/mc/task/getRealtimeMcTaskScopeList/' + id,
    method: 'get'
  })
}

// Task online and offline
export function updateReleaseJobTask(data) {
  return request({
    url: 'mc/task/updateReleaseJobTask',
    method: 'post',
    data
  })
}

// Scheduling online and offline
export function updateReleaseSchedule(data) {
  return request({
    url: 'mc/task/updateReleaseSchedule',
    method: 'post',
    data
  })
}

//Execute once
export function runJobOnce(data) {
  return request({
    url: `/mc/task/runJobOnce`,
    method: 'post',
    data
  })
}

// Get source system tree
export function sourceSystemTree(query) {
  return request({
    url: '/mc/task/sourceSystemTree',
    method: 'get',
    params: query
  })
}
// Delete collection tasks in batches
export function batchDeleteCheck(id) {
  return request({
    url: '/mc/task/batchDeleteCheck/' + id,
    method: 'get'
  });
}

export function taskProjects() { return request({ url: "/mc/task/projects", method: "get" }); }
export function taskSources(projectId) { return request({ url: "/mc/task/sources", method: "get", params: { projectId } }); }

export function taskSourceSystems(projectId) { return request({ url: "/mc/task/sourceSystems", method: "get", params: { projectId } }); }
export function taskLeaders(projectId) { return request({ url: "/mc/task/leaders", method: "get", params: { projectId } }); }
