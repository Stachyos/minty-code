import axios from 'axios'

export const api = axios.create({ baseURL: '/api', timeout: 20000 })
export const errorMessage = error => {
  const data = error.response?.data
  return (typeof data === 'string' ? data : data?.message || data?.error)
    || (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试。' : '暂时连接不上后端，请确认 Spring Boot 服务已启动。')
}
