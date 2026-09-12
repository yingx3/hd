function dt=time(Uw,Us)

    global dx dy g tol db

    hw=Uw(:,:,1);uw=Uw(:,:,2)./Uw(:,:,1);vw=Uw(:,:,3)./Uw(:,:,1);
    hs=Us(:,:,1);us=Us(:,:,2)./Us(:,:,1);vs=Us(:,:,3)./Us(:,:,1);
    I=hw<=db;uw(I)=0;vw(I)=0;
    I=hs<=db;us(I)=0;vs(I)=0;

    cw=sqrt(g.*hw);cs=sqrt(g.*hs);

    uW=sqrt(uw.^2+vw.^2);
    uS=sqrt(us.^2+vs.^2);

    c=max(cw,cs);u=max(uW,uS);

    kmax=0;kmax=max(kmax,max(max(u+c)));

    dt=tol*dx*dy/(2*kmax*(dx+dy));
