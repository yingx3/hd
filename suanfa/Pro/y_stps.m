function[Uw,Us]=y_stps(Uw,Us,dt)

    global n m bed r dy g db nn hG

    hw=Uw(:,:,1);uw=Uw(:,:,2)./Uw(:,:,1);vw=Uw(:,:,3)./Uw(:,:,1);
    hs=Us(:,:,1);us=Us(:,:,2)./Us(:,:,1);vs=Us(:,:,3)./Us(:,:,1);
    I=hw<=db;uw(I)=0;vw(I)=0;I=hs<=db;us(I)=0;vs(I)=0;


    k=1;
    Dty=k.*(vw-vs).*abs((vw-vs));
    Dty(hs<=1)=0;Dty(hw<=1)=0;


    N=zeros(n,m)+nn;N(hG==0)=0.3;
    w1=-Dty;w1(hs<=db)=0;
    w2=-vw.*sqrt(uw.^2+vw.^2).*g.*N.^2./(hw.^(1/3));
    w_s=w1+w2;
    w_s(uw.^2+vw.^2==0)=0;w_s(hw<=db)=0;
    A=Uw(:,:,3);
    I=A>=0&w_s<-(A/dt);w_s(I)=-A(I)/dt;I=A<0&w_s>-(A/dt);w_s(I)=-A(I)/dt;
    Uw(:,:,3)=Uw(:,:,3)+dt.*w_s;


    s1=r.*Dty;
    s2=-vs./sqrt(us.^2+vs.^2).*g.*hs.*tan(bed);
    s3=-r.*g.*hs.*([hw(:,2:m),hw(:,m)]-[hw(:,1),hw(:,1:m-1)])./(2*dy);s3(hs<=1)=0;
    s_s=s1+s2+s3;
    s_s(us.^2+vs.^2==0)=0;s_s(hs<=db)=0;
    A=Us(:,:,3);
    I=A>=0&s_s<-(A/dt);s_s(I)=-A(I)/dt;I=A<0&s_s>-(A/dt);s_s(I)=-A(I)/dt;
    Us(:,:,3)=Us(:,:,3)+dt.*s_s;
