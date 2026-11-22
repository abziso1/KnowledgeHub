# Markdown 正文存入 PostgreSQL，笔记图片存入 FastDFS

KnowledgeHub 需要将导入的 Markdown 直接变成可在线编辑、可搜索的笔记。选择在 PostgreSQL 存储笔记正文，在 FastDFS 存储图片文件。若将 Markdown 文件也存入 FastDFS，编辑保存还要协调文件写入与 PostgreSQL 搜索数据更新，而采用 PostgreSQL 存正文可以在一次数据库事务中完成正文及关联数据的更新。
