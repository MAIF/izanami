export function addOrUpdateInTree(segments, value, tree) {
  const [head, ...rest] = segments || [];

  if (!head) {
    return [];
  }

  if (tree.find(node => node.key === head)) {
    return tree.map(n => {
      if (n.key === head) {
        if (rest.length === 0) {
          return {...n, value};
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
    return [...tree, createTree(head, rest, value)];
  }
}

function createTree(subId, segments, value) {
  if(segments.length > 0) {
    const childs = segments.map((k) => ({key: k}))
      .reduceRight((acc, curr, index) => {
      curr.childs = [].concat(acc)
      let isLastItem = index === segments.length - 1;
      if (isLastItem) {
        curr.id = value.id
        curr.value = value
      }
      return curr
    }, []);

    return {key: subId, childs: [childs]}
  } else {
    return {key: subId, value, id: value.id, childs: []};
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
        return {key: n.key, childs: n.childs};
      } else if (n.key === head && next && n.childs) {
        return {...n, childs: this.deleteInTree([next, ...rest], n.childs)};
      } else {
        return n;
      }
    });
}
