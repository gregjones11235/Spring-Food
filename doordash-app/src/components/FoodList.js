import { useState } from "react";
import RestaurantList from "./RestaurantList";
import RestaurantMenu from "./RestaurantMenu";

// 首页：餐厅列表和餐厅详情两个视图来回切换。
// 搜索条件和页码保存在这里，从详情返回列表时还停在原来的搜索结果和页码上
const FoodList = () => {
  const [query, setQuery] = useState({ keyword: "", page: 1 });
  const [curRest, setCurRest] = useState(null);

  if (curRest) {
    return <RestaurantMenu restaurant={curRest} onBack={() => setCurRest(null)} />;
  }

  return <RestaurantList query={query} onQueryChange={setQuery} onSelect={setCurRest} />;
};

export default FoodList;
