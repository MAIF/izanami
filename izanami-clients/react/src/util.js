import { get } from 'lodash/get'

export const getIsActive = (features, path) => {
  return path
    .map(p => getIsFeatureActive(features, p))
    .every(p => p);
}

export const getIsFeatureActive = (features, path) => {
  const value = get(features, path) || { active: false };
  return value.active;
}

export const getCleanedArrayPath = (path) => {
  if (Array.isArray(path)) {
    return path
      .map(p => cleanPath(p));
  } else {
    return [cleanPath(path)];
  }
}

export const cleanPath = (path) => {
  return path.replace(/:/g, '.')
}

export const arrayPathToString = (arrayPath) => {
  return "[" + arrayPath.join(" & ") + "]";
}