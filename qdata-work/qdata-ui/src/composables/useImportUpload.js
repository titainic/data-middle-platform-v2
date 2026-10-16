import { ref } from 'vue';

// el-upload.submit() 在没有 ready 文件时不会触发回调，必须先检查队列再锁定按钮。
export default function useImportUpload({ upload, getUploadRef, onEmpty, onError, onSuccess }) {
  const importLoading = ref(false);
  const importFiles = ref([]);
  let activeFileId;

  function clearImport() {
    // 先失效当前文件，关闭后迟到的回调不能影响下一次导入。
    activeFileId = undefined;
    importLoading.value = false;
    upload.isUploading = false;
    getUploadRef()?.abort();
    getUploadRef()?.clearFiles();
    importFiles.value = [];
  }

  function resetImport() {
    clearImport();
    upload.updateSupport = 0;
  }

  function isActive(file) {
    return importLoading.value && file?.uid === activeFileId;
  }

  function handleFileUploadProgress(event, file) {
    if (isActive(file)) upload.isUploading = true;
  }

  function handleFileError(error, file) {
    if (!isActive(file)) return;
    clearImport();
    onError();
  }

  function handleFileSuccess(response, file) {
    if (!isActive(file)) return;
    clearImport();
    if (response?.code !== 200) {
      onError(response?.msg);
      return;
    }
    onSuccess(response);
  }

  function submitFileForm() {
    if (importLoading.value) return;
    const file = importFiles.value.find(file => file.status === 'ready' && file.raw);
    const uploadRef = getUploadRef();
    if (!file || !uploadRef) {
      onEmpty();
      return;
    }
    activeFileId = file.uid;
    importLoading.value = true;
    upload.isUploading = true;
    try {
      uploadRef.submit();
    } catch (error) {
      handleFileError(error, file);
    }
  }

  return {
    importLoading, importFiles, resetImport, submitFileForm,
    handleFileUploadProgress, handleFileSuccess, handleFileError,
  };
}
