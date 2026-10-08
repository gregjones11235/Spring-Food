import { useState } from "react";

const COLORS = ["#f5222d", "#fa8c16", "#faad14", "#52c41a", "#13c2c2", "#1890ff", "#2f54eb", "#722ed1", "#eb2f96"];

// 按名字算一个固定颜色，同一家店每次显示的占位色都一样
const colorOf = (name = "") => {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  }
  return COLORS[Math.abs(hash) % COLORS.length];
};

// 有图片就显示图片；没有图片或图片加载失败时，显示带首字母的色块
const Cover = ({ src, name, height = 160 }) => {
  const [failed, setFailed] = useState(false);

  if (src && !failed) {
    return (
      <img
        src={src}
        alt={name}
        onError={() => setFailed(true)}
        style={{ width: "100%", height, objectFit: "cover", display: "block" }}
      />
    );
  }

  return (
    <div
      style={{
        height,
        background: colorOf(name),
        color: "white",
        fontSize: height / 3,
        fontWeight: "bold",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {(name || "?").charAt(0).toUpperCase()}
    </div>
  );
};

export default Cover;
