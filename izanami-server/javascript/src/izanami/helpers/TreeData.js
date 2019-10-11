export function addOrUpdateInTree(segments, value, tree) {
  const [head, ...rest] = segments || [];

  if (!head) {
    return [];
  }

  if (tree.find(node => node.key === head)) {
    return tree.map(n => {
      if (n.key === head) {
        if (rest.length === 0) {
          return { ...n, value };
        }
        if (n.childs) {
          return {
            ...n,
            childs: [...addOrUpdateInTree(rest, value, n.childs)]
          };
        } else {
          return {
            ...n,
            childs: [createTree(head, segments, value)]
          };
        }
      } else {
        return n;
      }
    });
  } else {
    return [...tree, createTree(head, segments, value)];
  }
}

function createTree(key, segments, value) {
  const [head, ...rest] = segments || [];
  const id = `${key}:${head}`;
  if (rest.length > 0) {
    return {
      key: head,
      childs: [createTree(id, rest, value)]
    };
  } else {
    return { key: head, value, id };
  }
}

export function deleteInTree(segments, tree) {
  const [head, next, ...rest] = segments || [];

  if (!head) {
    return tree;
  }
  return tree
    .filter(n => !(n.key === head && (!n.childs || n.childs.length === 0)))
    .map(n => {
      if (n.key === head && !next) {
        return { key: n.key, childs: n.childs };
      } else if (n.key === head && next && n.childs) {
        return { ...n, childs: this.deleteInTree([next, ...rest], n.childs) };
      } else {
        return n;
      }
    });
}
