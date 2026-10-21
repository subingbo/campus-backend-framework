import axios from 'axios'
import http from './http'

export const authApi = {
  login: (payload) => http.post('/auth/login', payload),
  register: (payload) => http.post('/auth/register', payload)
}

export const mapApi = {
  list: () => http.get('/maps'),
  detail: (id) => http.get(`/maps/${id}`),
  createGroup: (payload) => http.post('/groups', payload),
  joinGroup: (payload) => http.post('/groups/join', payload),
  listPlaces: (id) => http.get(`/maps/${id}/places`),
  addPlace: (id, payload) => http.post(`/maps/${id}/places`, payload),
  sharePlace: (id, placeId, payload = {}) => http.post(`/maps/${id}/places/${placeId}`, payload),
  removePlace: (id, placeId) => http.delete(`/maps/${id}/places/${placeId}`),
  updateMemberRole: (id, userId, payload) => http.put(`/maps/${id}/members/${userId}`, payload),
  removeMember: (id, userId) => http.delete(`/maps/${id}/members/${userId}`),
  listComments: (id, placeId) => http.get(`/maps/${id}/places/${placeId}/comments`),
  addComment: (id, placeId, payload) => http.post(`/maps/${id}/places/${placeId}/comments`, payload)
}

export const placeApi = {
  categories: () => http.get('/place-categories'),
  update: (id, payload) => http.put(`/places/${id}`, payload),
  get: (id) => http.get(`/places/${id}`),
  feed: (id) => http.get(`/places/${id}/feed`),
  mapsForPlace: (id) => http.get(`/places/${id}/maps`)
}

export const activityApi = {
  listByPlace: (placeId) => http.get(`/places/${placeId}/activities`),
  create: (payload) => http.post('/activities', payload),
  update: (id, payload) => http.put(`/activities/${id}`, payload),
  cancel: (id) => http.delete(`/activities/${id}`),
  members: (id) => http.get(`/activities/${id}/members`),
  join: (id) => http.post(`/activities/${id}/join`),
  leave: (id) => http.delete(`/activities/${id}/join`),
  checkin: (id, code) => http.post(`/activities/${id}/checkin`, { code }),
  mine: (role = 'all') => http.get('/activities/mine', { params: { role } }),
  invite: (id, userId) => http.post(`/activities/${id}/invites`, { userId }),
  pendingInvites: () => http.get('/activity-invites/pending'),
  acceptInvite: (inviteId) => http.post(`/activity-invites/${inviteId}/accept`),
  declineInvite: (inviteId) => http.post(`/activity-invites/${inviteId}/decline`)
}

export const scheduleApi = {
  shareOptions: () => http.get('/schedules/share'),
  setShare: (payload) => http.put('/schedules/share', payload)
}

export const friendApi = {
  list: () => http.get('/friends'),
  requests: () => http.get('/friends/requests'),
  search: (keyword) => http.get('/friends/search', { params: { keyword } }),
  sendRequest: (payload) => http.post('/friends/requests', payload),
  accept: (id) => http.post(`/friends/requests/${id}/accept`),
  reject: (id) => http.post(`/friends/requests/${id}/reject`),
  remove: (userId) => http.delete(`/friends/${userId}`)
}

export const chatApi = {
  conversations: () => http.get('/conversations'),
  createPrivate: (userId) => http.post('/conversations/private', { userId }),
  forMap: (mapId) => http.get(`/conversations/maps/${mapId}`),
  forPlace: (placeId) => http.get(`/conversations/places/${placeId}`),
  messages: (id, beforeId = null, limit = 50) =>
    http.get(`/conversations/${id}/messages`, { params: { beforeId, limit } })
}

export const assistantApi = {
  createChat: () => http.post('/assistant/chats'),
  listChats: () => http.get('/assistant/chats'),
  detail: (id) => http.get(`/assistant/chats/${id}`),
  savePlan: (id, body) => http.post(`/assistant/chats/${id}/plan/save`, body),
  revisePlan: (id, body) => http.post(`/assistant/chats/${id}/plan/revise`, body),
  route: (params) => http.get('/assistant/routes', { params })
}

export const traceApi = {
  list: (params) => http.get('/agent/traces', { params }),
  detail: (sessionId) => http.get(`/agent/traces/${sessionId}`),
  events: (sessionId, params) => http.get(`/agent/traces/${sessionId}/events`, { params }),
  /** 导出是 NDJSON 原始流，不能走统一 Result 拦截器；单独带 token 请求 blob */
  exportJsonl: (sessionId) => {
    const token = localStorage.getItem('accessToken')
    return axios.get(`/api/agent/traces/${sessionId}/export`, {
      params: { format: 'jsonl' },
      responseType: 'blob',
      timeout: 60000,
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    }).then((res) => res.data)
  }
}
