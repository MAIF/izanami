import {Component, OnInit} from '@angular/core';
import {AppServicesService} from "../app-services.service";
import {Router} from "@angular/router";
import {FormBuilder, FormGroup, Validators} from "@angular/forms";

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent implements OnInit {

  loginFormGroup: FormGroup;
  error: boolean;

  constructor(private appService: AppServicesService, private router: Router, private fb: FormBuilder) {
    this.createForm();
  }

  createForm() {
    this.loginFormGroup = this.fb.group({
      email: ['', [Validators.required, Validators.email]]
    });
  }

  ngOnInit() {

    this.error = false;
  }

  doLogin() {
    if (this.loginFormGroup.valid)
      this.appService.login(this.loginFormGroup.get('email').value)
        .subscribe(res => {
          this.error = res !== null;

          if (!this.error)
            this.router.navigate([""]);
        });
  }

  get email() {
    return this.loginFormGroup.get('email');
  }
}


class Login {
  email: string
}
