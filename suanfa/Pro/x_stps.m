function[Uw,Us]=x_stps(Uw,Us,dt)

    global n m bed r dx g db nn hG

    hw=Uw(:,:,1);uw=Uw(:,:,2)./Uw(:,:,1);vw=Uw(:,:,3)./Uw(:,:,1);
    hs=Us(:,:,1);us=Us(:,:,2)./Us(:,:,1);vs=Us(:,:,3)./Us(:,:,1);
    I=hw<=db;uw(I)=0;vw(I)=0;I=hs<=db;us(I)=0;vs(I)=0;


    k=1;
    Dtx=k.*(uw-us).*abs((uw-us));
    Dtx(hs<=1)=0;Dtx(hw<=1)=0;


    N=zeros(n,m)+nn;N(hG==0)=0.3;
    w1=-Dtx;
    w2=-uw.*sqrt(uw.^2+vw.^2).*g.*N.^2./(hw.^(1/3));
    w_s=w1+w2;
    w_s(uw.^2+vw.^2==0)=0;w_s(hw<=db)=0;
    A=Uw(:,:,2);
    I=A>=0&w_s<-(A/dt);w_s(I)=-A(I)/dt;I=A<0&w_s>-(A/dt);w_s(I)=-A(I)/dt;
    Uw(:,:,2)=Uw(:,:,2)+dt.*w_s;


    s1=r.*Dtx;
    s2=-us./sqrt(us.^2+vs.^2).*g.*hs.*tan(bed);
    s3=-r.*g.*hs.*([hw(2:n,:);hw(n,:)]-[hw(1,:);hw(1:n-1,:)])./(2*dx);s3(hs<=1)=0;
    s_s=s1+s2+s3;
    s_s(us.^2+vs.^2==0)=0;s_s(hs<=db)=0;
    A=Us(:,:,2);
    I=A>=0&s_s<-(A/dt);s_s(I)=-A(I)/dt;I=A<0&s_s>-(A/dt);s_s(I)=-A(I)/dt;
    Us(:,:,2)=Us(:,:,2)+dt.*s_s;
