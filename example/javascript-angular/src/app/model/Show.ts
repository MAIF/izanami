import {Season} from "./Season";

export class Show {

  constructor(image: string, title: string, description: string, id: string) {
    this.image = image;
    this.title = title;
    this.description = description;
    this.id = id;
  }

  image: string;
  title: string;
  description: string;
  id: string;
  seasons: Season[];
}
