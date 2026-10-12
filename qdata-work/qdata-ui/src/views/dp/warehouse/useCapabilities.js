import {ref} from 'vue';
import request from '@/utils/request';

export function useWarehouseCapabilities(spaceId) {
  const capabilities=ref({});
  let latest=0;
  async function loadCapabilities() {
    const ticket=++latest;
    const id=spaceId();
    capabilities.value={};
    if(!id)return false;
    const response=await request({url:`/dp/warehouse/spaces/${id}/capabilities`,headers:{hideErrorMessage:true}});
    if(ticket!==latest || String(id)!==String(spaceId()))return false;
    capabilities.value=response.data || {};
    return true;
  }
  return {capabilities,loadCapabilities};
}
