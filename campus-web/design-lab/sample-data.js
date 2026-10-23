/* 静态样板数据：与真实接口字段对齐，仅用于视觉评审，不接后端。 */
window.LAB_DATA = {
  user: { nickname: '林晓', initial: '林' },
  maps: [
    {
      id: 51,
      name: '周末去哪儿',
      role: 'OWNER',
      members: 7,
      places: 6,
      description: '周六下午的校园小聚：图书馆、咖啡、运动场和南门集合点。',
      selected: true
    },
    {
      id: 53,
      name: '校园美食地图',
      role: 'EDITOR',
      members: 9,
      places: 5,
      description: '南门奶茶、夜市小吃和食堂窗口，周末觅食集合。',
      selected: false
    }
  ],
  places: [
    {
      id: 1,
      name: '东区图书馆',
      category: '学习',
      address: '大学城中环东路 178 号',
      description: '二楼静音区插座多，适合复习和小组讨论；下午三点后靠窗位置更安静。',
      selected: true
    },
    {
      id: 2,
      name: '南门咖啡',
      category: '美食',
      address: '南门内侧 12 栋旁',
      description: '桂花拿铁不错，适合聊天；下午三点后靠窗位置安静。'
    },
    {
      id: 3,
      name: '西区运动场',
      category: '运动',
      address: '西区宿舍南侧',
      description: '傍晚人多，跑步尽量避开 18:00–19:00。'
    },
    {
      id: 4,
      name: '校车站',
      category: '交通',
      address: '南门东侧',
      description: '周末班次较少，出门前先看时刻表。'
    },
    {
      id: 5,
      name: '一饭·二楼',
      category: '美食',
      address: '第一食堂二楼',
      description: '烧腊窗口排队最长，11:30 前到。'
    },
    {
      id: 6,
      name: '快递驿站',
      category: '生活服务',
      address: '生活区 3 栋架空层',
      description: '取快递和寄件点，中午是高峰。'
    }
  ],
  members: ['林晓', '陈晨', '王宁', '赵可', '周予', '刘畅', '孙悦'],
  activity: {
    title: '东区图书馆 → 南门咖啡下午小聚',
    time: '今天 15:00–16:55',
    meta: '7 人已报名 · 2 个站点',
    status: '已确认'
  },
  comments: [
    { name: '陈晨', text: '周末班次少，注意时间。', time: '10-22 19:27' },
    { name: '王宁', text: '图书馆二楼靠窗那排插座多，可以早点去占座。', time: '10-22 18:05' }
  ],
  summary: {
    distance: '距南门约 600 米',
    updated: '10 分钟前有人更新过'
  }
};
