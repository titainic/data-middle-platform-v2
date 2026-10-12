export async function logoutAndClear(user, requestLogout, storage, removeToken) {
  try {
    await requestLogout();
  } finally {
    user.token = '';
    user.id = '';
    user.name = '';
    user.nickName = '';
    user.phonenumber = '';
    user.avatar = '';
    user.roles = [];
    user.permissions = [];
    user.projectId = '';
    storage.removeItem('qdataProjectId');
    removeToken();
  }
}

export async function finishLogout(providerLogout, localLogout, navigate) {
  try {
    if (providerLogout) await providerLogout();
  } finally {
    try {
      await localLogout();
    } finally {
      navigate();
    }
  }
}
