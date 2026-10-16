import { onBeforeUnmount, onMounted, ref } from 'vue';
import { currentUser } from '@/api/att/project/project.js';
import { getRoutersDpp } from '@/api/system/menu.js';
import bus from '@/utils/bus.js';
import { PROJECT_OPTIONS_CHANGED } from '@/utils/projectOptions.js';

export default function useProjectOptions(userStore, permissionStore,
  beginMenuUpdate = () => routes => permissionStore.updateTopbarRoutes(routes)) {
  const projectOptions = ref([]);
  const projectOptionsLoading = ref(false);
  let revision = 0;
  let pending = null;
  let disposed = false;
  const sameId = (left, right) => left != null && right != null && String(left) === String(right);

  function clearProjectRoutes() {
    beginMenuUpdate()(permissionStore.topbarRouters.map(route =>
      route.path === '/dpp' ? { ...route, children: [] } : route));
  }

  function clearSelection() {
    projectOptions.value = [];
    userStore.projectId = null;
    userStore.projectCode = '';
    localStorage.removeItem('qdataProjectId');
    clearProjectRoutes();
  }

  function refreshProjectOptions() {
    if (disposed) return Promise.resolve();
    revision++;
    // Coalesce synchronous notifications and serialize changes arriving during a request.
    if (pending) return pending;
    projectOptionsLoading.value = true;
    let requestedRevision = 0;
    pending = Promise.resolve().then(async () => {
      do {
        requestedRevision = revision;
        const userId = userStore.id;
        let requestedProjectId = null;
        if (!userId) {
          clearSelection();
          break;
        }
        try {
          const response = await currentUser();
          if (disposed || requestedRevision !== revision || !sameId(userId, userStore.id)) continue;
          if (response.code !== 200 || !Array.isArray(response.data)) throw new Error('Invalid project list');
          // The administrator endpoint also returns disabled projects.
          projectOptions.value = response.data.filter(project => project.id != null && project.id !== '' &&
            ![false, 0, '0'].includes(project.validFlag));
          const currentId = userStore.projectId || localStorage.getItem('qdataProjectId');
          const selected = projectOptions.value.find(project => sameId(project.id, currentId)) || projectOptions.value[0];
          if (!selected) {
            clearSelection();
            continue;
          }
          const changed = !sameId(selected.id, userStore.projectId);
          userStore.projectId = selected.id;
          userStore.projectCode = selected.code || '';
          localStorage.setItem('qdataProjectId', selected.id);
          if (changed) clearProjectRoutes();
          requestedProjectId = selected.id;
          const commitRoutes = beginMenuUpdate();
          const routes = await getRoutersDpp(selected.id);
          if (!disposed && requestedRevision === revision && sameId(userId, userStore.id) &&
              sameId(selected.id, userStore.projectId)) {
            if (routes.code !== 200 || !Array.isArray(routes.data)) throw new Error('Invalid project routes');
            await commitRoutes(routes.data);
          }
        } catch {
          // The request interceptor reports HTTP/business errors. Do not retain unverified IDs.
          if (!disposed && requestedRevision === revision && sameId(userId, userStore.id) &&
              (requestedProjectId == null || sameId(requestedProjectId, userStore.projectId))) clearSelection();
        }
      } while (!disposed && requestedRevision !== revision);
    }).finally(() => {
      pending = null;
      if (disposed) return;
      // A notification can arrive after the loop exits but before this finalizer runs.
      if (requestedRevision !== revision) return refreshProjectOptions();
      projectOptionsLoading.value = false;
    });
    return pending;
  }

  bus.on(PROJECT_OPTIONS_CHANGED, refreshProjectOptions);
  onMounted(refreshProjectOptions);
  onBeforeUnmount(() => {
    disposed = true;
    bus.off(PROJECT_OPTIONS_CHANGED, refreshProjectOptions);
  });

  return { projectOptions, projectOptionsLoading, refreshProjectOptions };
}
